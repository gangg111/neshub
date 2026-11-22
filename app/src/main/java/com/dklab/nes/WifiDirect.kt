package com.dklab.nes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Netplay over Wi-Fi Direct: two phones, no router or hotspot. Kotlin port of t3native's
 * WifiDirect.java (github.com/gangg111/t3native, android/java/.../WifiDirect.java) with its
 * lessons kept: the host makes its own group (group owner, 192.168.49.1) and advertises the
 * game as DNS-SD service [SERVICE]; the guest lists the hosts it finds and connects (the
 * host's phone may ask its player to accept); then the hub's own UDP protocol runs in the
 * group, the guest joining the group owner's address ([address]). A connect waits until the
 * device is in the phone's own peer list (Fold7 dropped it otherwise), BUSY is retried, a
 * phone already in a group keeps it, only a group made here is removed.
 *
 * Every WifiP2pManager call runs on the main thread; [hosts], [address], [note] are read by
 * other threads.
 */
class WifiDirect(private val context: Context, private val activity: () -> Activity?, private val log: (String) -> Unit) {

    private enum class Role { NONE, HOST, GUEST }

    private val main = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var role = Role.NONE
    private var name = ""
    private var afterPermission: (() -> Unit)? = null
    private var ownGroup = false
    private var groupOwner = ""
    private var p2pState = -1
    private var waitingOn = false
    private var target = ""
    private var connectTries = 0
    private var peerWaits = 0

    private val hostList = LinkedHashMap<String, String>()   // device address -> name
    @Volatile var address = ""; private set
    @Volatile var note = ""; private set

    @Synchronized fun hosts(): Map<String, String> = LinkedHashMap(hostList)

    fun host(hostName: String) = main.post { stopNow(); role = Role.HOST; name = hostName; log("host $hostName"); withPermission { startHost() } }
    fun search() = main.post { stopNow(); role = Role.GUEST; log("search"); withPermission { startSearch() } }
    fun stop() = main.post { stopNow() }

    fun connect(deviceAddress: String) = main.post {
        if (role != Role.GUEST || manager == null) return@post
        if (deviceAddress == GROUP_HOST) { log("join the group's owner $groupOwner"); note = ""; address = groupOwner; return@post }
        log("connect to $deviceAddress")
        note = "Łączenie…"
        target = deviceAddress; connectTries = 0; peerWaits = 0
        tryConnect()
    }

    /** The session began: no more discovery (scans on the group's channel delay the game). */
    fun settle() = main.post {
        main.removeCallbacks(refresh)
        manager?.let { m -> channel?.let { m.stopPeerDiscovery(it, quiet()) } }
        log("session: discovery stopped")
    }

    fun permissionAnswered(requestCode: Int, results: IntArray) {
        if (requestCode != PERMISSION_REQUEST) return
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        log("permission " + if (granted) "granted" else "refused")
        val then = afterPermission; afterPermission = null
        if (granted) { note = ""; then?.invoke() } else note = "Zezwól na dostęp do urządzeń w pobliżu"
    }

    // ------------------------------------------------------------- main thread

    private fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    private fun withPermission(then: () -> Unit) {
        // Android 12 and older find no phones with the location off, and say nothing.
        if (Build.VERSION.SDK_INT in 28..32) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !lm.isLocationEnabled) note = "Włącz lokalizację"
        }
        if (context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED) { then(); return }
        afterPermission = then
        note = "Zezwól na dostęp do urządzeń w pobliżu"
        activity()?.requestPermissions(arrayOf(permission()), PERMISSION_REQUEST)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun open(): Boolean {
        if (manager != null) return true
        val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) { log("no WifiP2pManager"); note = "Ten telefon nie ma Wi-Fi Direct"; return false }
        manager = m
        channel = m.initialize(context, Looper.getMainLooper()) { log("channel lost"); channel = null; manager = null }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = onBroadcast(i) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun onBroadcast(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val on = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                log("Wi-Fi Direct " + if (on) "on" else "off")
                if (!on) note = "Włącz Wi-Fi"
                else {
                    if (note == "Włącz Wi-Fi") note = ""
                    if (waitingOn && role != Role.NONE) { waitingOn = false; if (role == Role.HOST) startHost() else startSearch() }
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val m = manager ?: return; val ch = channel ?: return
                m.requestConnectionInfo(ch) { info ->
                    if (info == null || !info.groupFormed) { log("no group"); address = ""; return@requestConnectionInfo }
                    val go = info.groupOwnerAddress?.hostAddress ?: ""
                    log("group: " + if (info.isGroupOwner) "this phone owns it" else "owner at $go")
                    if (role == Role.GUEST && !info.isGroupOwner && ownGroup && go.isNotEmpty()) { note = ""; address = go }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHost() {
        if (!open()) return
        val m = manager!!; val ch = channel!!
        // A group this phone already owns is kept; one where it is a guest goes, then a new one.
        m.requestGroupInfo(ch) { group ->
            if (group != null && group.isGroupOwner) { log("host in the group already owned"); advertise(); return@requestGroupInfo }
            if (group != null) { log("leave the group to host"); m.removeGroup(ch, quiet()) }
            main.postDelayed({
                call("createGroup", { l -> manager?.createGroup(channel, l) }) { ownGroup = true; log("group made"); advertise() }
            }, if (group != null) 1000L else 0L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun advertise() {
        val m = manager ?: return
        val info = WifiP2pDnsSdServiceInfo.newInstance(name, SERVICE, mapOf("n" to name))
        m.clearLocalServices(channel, quiet())
        call("addLocalService", { l -> manager?.addLocalService(channel, info, l) }) { log("game advertised") }
        refresh.run()
    }

    @SuppressLint("MissingPermission")
    private fun startSearch() {
        if (!open()) return
        val m = manager!!; val ch = channel!!
        synchronized(this) { hostList.clear() }
        address = ""; groupOwner = ""
        m.requestConnectionInfo(ch) { info ->
            if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                groupOwner = info.groupOwnerAddress.hostAddress ?: ""
                log("already in a group, its owner at $groupOwner")
                synchronized(this) { hostList[GROUP_HOST] = "Gospodarz grupy" }
            } else if (info != null && info.groupFormed) {
                m.requestGroupInfo(ch) { group ->
                    if (group != null && group.clientList.isEmpty()) { log("empty group left over: removed"); m.removeGroup(ch, quiet()) }
                }
            }
        }
        m.setDnsSdResponseListeners(ch, { instance, type, device ->
            if (type != null && type.startsWith(SERVICE) && device != null) synchronized(this) {
                if (!hostList.containsKey(device.deviceAddress)) log("game $instance at ${device.deviceAddress}")
                hostList[device.deviceAddress] = instance
            }
        }, { domain, record, device ->
            val n = record?.get("n")
            if (domain != null && domain.contains(SERVICE) && n != null && device != null) synchronized(this) { hostList[device.deviceAddress] = n }
        })
        m.clearServiceRequests(ch, quiet())
        call("addServiceRequest", { l -> manager?.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), l) }, then = null)
        refresh.run()
    }

    // Discovery again every REFRESH_MS: the host stays findable, the guest keeps asking.
    private val refresh: Runnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val m = manager ?: return
            if (role == Role.NONE) return
            if (!p2pOn()) { main.postDelayed(this, REFRESH_MS); return }
            m.discoverPeers(channel, quiet())
            if (role == Role.GUEST) m.discoverServices(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    log("discoverServices: " + reasonName(reason))
                    if (reason == WifiP2pManager.NO_SERVICE_REQUESTS) manager?.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), quiet())
                }
            })
            main.postDelayed(this, REFRESH_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect() {
        val m = manager ?: return
        if (role != Role.GUEST || target.isEmpty()) return
        m.requestPeers(channel) { peers ->
            val device = peers?.get(target)
            if (device == null) {
                if (++peerWaits > PEER_WAITS) { log("$target not nearby in $PEER_WAITS s"); note = "Nie znaleziono gracza 1"; return@requestPeers }
                m.discoverPeers(channel, quiet())
                main.postDelayed({ tryConnect() }, 1000)
                return@requestPeers
            }
            val config = WifiP2pConfig().apply { deviceAddress = target; groupOwnerIntent = 0; wps.setup = WpsInfo.PBC }
            ownGroup = true
            note = "Zaakceptuj połączenie na telefonie gracza 1"
            m.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { log("connect: started") }
                override fun onFailure(reason: Int) {
                    val again = (reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY) && ++connectTries < CONNECT_TRIES
                    log("connect: " + reasonName(reason) + if (again) ", again" else "")
                    if (again) { manager?.discoverPeers(channel, quiet()); main.postDelayed({ tryConnect() }, 2000) }
                    else note = "Wi-Fi Direct nie połączył telefonów"
                }
            })
        }
    }

    private fun stopNow() {
        main.removeCallbacks(refresh)
        afterPermission = null
        val m = manager; val ch = channel
        if (m != null && ch != null) {
            m.stopPeerDiscovery(ch, quiet()); m.clearServiceRequests(ch, quiet()); m.clearLocalServices(ch, quiet())
            if (ownGroup) { log("group removed"); m.removeGroup(ch, quiet()) }
        }
        ownGroup = false; groupOwner = ""; target = ""; waitingOn = false; p2pState = -1
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null; manager = null; channel = null; role = Role.NONE
        synchronized(this) { hostList.clear() }
        address = ""; note = ""
    }

    /** A WifiP2pManager call, tried again on BUSY; [then] after it succeeds. */
    private fun call(what: String, call: (WifiP2pManager.ActionListener) -> Unit, tries: Int = RETRIES, then: (() -> Unit)?) {
        if (manager == null) return
        call(object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("$what: done"); then?.invoke() }
            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && p2pState != -1 && !p2pOn()) {
                    log("$what: BUSY, Wi-Fi Direct off: waiting for it"); waitingOn = true; note = "Włącz Wi-Fi"; return
                }
                log("$what: " + reasonName(reason))
                when {
                    reason == WifiP2pManager.BUSY && tries > 0 -> main.postDelayed({ call(what, call, tries - 1, then) }, RETRY_MS)
                    reason == WifiP2pManager.P2P_UNSUPPORTED -> note = "Ten telefon nie ma Wi-Fi Direct"
                    reason == WifiP2pManager.BUSY -> note = "Wi-Fi Direct zajęty, spróbuj ponownie"
                    else -> note = "Wi-Fi Direct nie zadziałał"
                }
            }
        })
    }

    private fun p2pOn() = p2pState == -1 || p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED

    private fun reasonName(r: Int) = when (r) {
        WifiP2pManager.ERROR -> "ERROR"; WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"; WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"; else -> "reason $r"
    }

    private fun quiet() = object : WifiP2pManager.ActionListener { override fun onSuccess() {}; override fun onFailure(reason: Int) {} }

    companion object {
        const val SERVICE = "_neshub._udp"
        const val GROUP_HOST = "@group"
        const val PERMISSION_REQUEST = 27031
        private const val REFRESH_MS = 10_000L
        private const val RETRIES = 8
        private const val RETRY_MS = 1500L
        private const val CONNECT_TRIES = 4
        private const val PEER_WAITS = 15
    }
}
