package com.dklab.nes

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.lang.ref.WeakReference
import java.net.InetSocketAddress

/**
 * Netplay for the whole app (the hub finds a game, the player activity plays it, so the
 * connection outlives activities). Two ways to meet, as in t3native:
 *  - [Mode.WIFI]: both phones on one router's Wi-Fi (or one's hotspot); UDP broadcast finds games.
 *  - [Mode.DIRECT]: Wi-Fi Direct, no router; the host's group, the guest connects ([WifiDirect]).
 * A thread polls the [NetSession] every few ms, also while the game is paused, so the
 * other phone keeps hearing from this one.
 */
object Net {
    enum class Mode { WIFI, DIRECT }

    @Volatile var session: NetSession? = null; private set
    @Volatile var mode = Mode.WIFI; private set
    private var direct: WifiDirect? = null
    private var poller: Thread? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var activityRef = WeakReference<Activity>(null)
    private val t0 = SystemClock.elapsedRealtime()
    private var joinedDirect = false
    /** Last lines of the network log (shown in the hub's netplay sheet when something fails). */
    val logLines = ArrayDeque<String>()

    /** Why the last host() failed (socket error), for the player. */
    @Volatile var lastError = ""
    fun now() = (SystemClock.elapsedRealtime() - t0) / 1000.0

    /** The activity on screen (permission dialogs, receivers); call from onResume. */
    fun attach(a: Activity) { activityRef = WeakReference(a); appContext = a.applicationContext }

    fun log(line: String) {
        Log.i("neshub", line)
        synchronized(logLines) { logLines.addLast(line); while (logLines.size > 40) logLines.removeFirst() }
        fileLine(line)
    }

    // ---- the session's log in Downloads (neshub_net_<time>.txt), as t3native does: both phones
    // write one, each line with the time, so the two can be read side by side afterwards.
    private var appContext: Context? = null
    private var logOut: java.io.OutputStream? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)

    private fun fileLine(line: String) {
        val c = appContext ?: return
        if (session == null && logOut == null) return
        val stamped = synchronized(clock) { clock.format(java.util.Date()) } + " " + line + "\n"
        writer.execute {
            runCatching {
                if (logOut == null) logOut = openLog(c)
                logOut?.write(stamped.toByteArray()); logOut?.flush()
            }
        }
    }

    private fun openLog(c: Context): java.io.OutputStream? {
        val name = "neshub_net_" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date()) + ".txt"
        val out = if (Build.VERSION.SDK_INT >= 29) {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            val uri = c.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            c.contentResolver.openOutputStream(uri, "w")
        } else java.io.FileOutputStream(java.io.File(c.getExternalFilesDir(null), name))
        out?.write(("NES Hub network log: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${versionName(c)}\n" +
            "stat lines (each second): ran = frames played, wait = frames that waited for the other phone, " +
            "catchup = double steps, buffer = records in hand (player 2), lead = how many steps ahead player 2's pads came (player 1)\n").toByteArray())
        return out
    }

    private fun closeLog() { val o = logOut; logOut = null; writer.execute { runCatching { o?.close() } } }

    /** The phone's name as the other player sees it. */
    fun deviceName(c: Context): String {
        val n = runCatching { Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return (if (n.isNullOrBlank()) Build.MODEL else n).take(24)
    }

    private fun build(c: Context): Int =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt() }.getOrDefault(0)

    fun versionName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" }.getOrDefault("")

    private fun newSession(c: Context) = NetSession(build(c), deviceName(c), versionName(c)).also { it.logger = ::log }

    /** Player 1: opens the hub to a second player (the game is picked together later). */
    fun host(c: Context, m: Mode): Boolean {
        stop()
        mode = m
        val s = newSession(c)
        if (!s.host(now())) { log("net: host failed (${s.reason})"); lastError = s.error; return false }
        session = s
        lock(c, true)
        if (m == Mode.DIRECT) wifiDirect(c).host(deviceName(c))
        startPoller()
        return true
    }

    /** Player 2: looks for games. */
    fun search(c: Context, m: Mode) {
        stop()
        mode = m
        val s = newSession(c)
        s.search(now())
        session = s
        joinedDirect = false
        lock(c, true)
        if (m == Mode.DIRECT) wifiDirect(c).search()
        startPoller()
    }

    /** A game the guest sees: [ok] = the same app version (only then joinable); [version] the host's. */
    data class Found(val id: String, val name: String, val version: String, val ok: Boolean)

    fun offers(): List<Found> {
        val s = session ?: return emptyList()
        val udp = s.offers().map { Found("${it.address.address.hostAddress}:${it.address.port}", it.name, it.version, s.compatible(it)) }
        if (mode == Mode.WIFI) return udp
        // Wi-Fi Direct: only its own hosts, not the same phones seen through a router both are
        // on (joining those would not be Wi-Fi Direct); a game already found inside a Wi-Fi
        // Direct group (192.168.49.x) stands in for its phone's entry.
        val inGroup = udp.filter { it.id.startsWith("192.168.49.") }
        val wd = (direct?.hosts() ?: emptyMap()).filter { (_, name) -> inGroup.none { it.name == name } }
            .map { (addr, name) -> Found(addr, name, "Wi-Fi Direct", true) }
        return wd + inGroup
    }

    fun join(id: String) {
        val s = session ?: return
        val hp = id.split(":")
        if (hp.size == 2 && hp[0].count { it == '.' } == 3) { s.join(InetSocketAddress(hp[0], hp[1].toInt()), now()); return }
        // A Wi-Fi Direct host: connect, then join its address once the group is formed (poller).
        direct?.connect(id); joinedDirect = true
    }

    /** What the player should know (permission, Wi-Fi off, ...), else "". */
    fun note(): String = direct?.note ?: ""

    fun stop() {
        // BYE goes out on a thread of its own (no network on the UI thread)
        session?.let { s -> Thread { s.close() }.start() }; session = null
        direct?.stop()
        poller?.interrupt(); poller = null
        activityRef.get()?.let { lock(it, false) }
        closeLog()
    }

    /** Connected and this phone is player 1 / player 2. */
    fun isHost() = session?.let { it.isHost && it.phase == NetSession.Phase.PLAYING } ?: false
    fun isGuest() = session?.let { !it.isHost && it.phase == NetSession.Phase.PLAYING } ?: false

    /** The session began: Wi-Fi Direct stops scanning (it slows the group's link). */
    fun settle() { direct?.settle() }

    fun permissionAnswered(requestCode: Int, results: IntArray) { direct?.permissionAnswered(requestCode, results) }

    private fun wifiDirect(c: Context): WifiDirect =
        direct ?: WifiDirect(c.applicationContext, { activityRef.get() }, ::log).also { direct = it }

    /** Called (on the network thread) when what the hub shows changed: phase, peer, the
     *  selected cassette, a game started or ended. The hub pushes it to its page at once. */
    @Volatile var onChange: (() -> Unit)? = null

    /** This phone's addresses, read once a few seconds (asking Android is slow on old phones). */
    @Volatile private var addrCache = ""
    @Volatile private var addrAt = 0.0
    fun addresses(): String {
        if (now() - addrAt > 5.0 || addrAt == 0.0) { addrCache = NetSession.localAddresses().joinToString(); addrAt = now() }
        return addrCache
    }

    private fun startPoller() {
        poller?.interrupt()
        poller = Thread({
            var seen = ""
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val s = session ?: break
                    s.poll(now())
                    val sig = "${s.phase}|${s.peerName}|${s.hubSel}|${s.launched}|${s.epoch}"
                    if (sig != seen) { seen = sig; onChange?.invoke() }
                    // Wi-Fi Direct guest: the group came up, join the owner's address.
                    val d = direct
                    if (joinedDirect && d != null && d.address.isNotEmpty() && s.phase == NetSession.Phase.SEARCHING) {
                        s.join(InetSocketAddress(d.address, NetSession.PORT), now())
                    }
                    // a game needs every few ms; the hub's lobby does with less (old phones)
                    Thread.sleep(if (s.launched || s.phase != NetSession.Phase.PLAYING) 4 else 12)
                }
            } catch (_: InterruptedException) {}
        }, "neshub-net").apply { isDaemon = true; start() }
    }

    /** Wi-Fi lets broadcasts (the searches for games) in only with this held. */
    @Synchronized private fun lock(c: Context, on: Boolean) {
        try {
            if (on && multicast == null) {
                val w = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                multicast = w.createMulticastLock("neshub").apply { setReferenceCounted(false); acquire() }
                // No Wi-Fi power save while two phones play: its wake-ups are what makes the
                // packets late in bursts (Android's low-latency mode, meant for games).
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = runCatching { w.createWifiLock(mode, "neshub-net").apply { setReferenceCounted(false); acquire() } }.getOrNull()
            } else if (!on) {
                multicast?.release(); multicast = null
                runCatching { wifiLock?.release() }; wifiLock = null
            }
        } catch (_: RuntimeException) { multicast = null }
    }
}
