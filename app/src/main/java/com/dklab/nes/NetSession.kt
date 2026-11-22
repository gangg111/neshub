package com.dklab.nes

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Netplay's connection between two copies of the hub on one local network (a router's Wi-Fi
 * or a Wi-Fi Direct group), after t3native's NetSession (github.com/gangg111/t3native,
 * src/net/net_session.*). Plain JVM, no Android: tools/NetSessionTest.kt runs it on a PC.
 *
 * The host (player 1) runs the game; the guest (player 2) sends its pad for each step and runs
 * the steps the host sends back ([NetPlay] writes and reads them). One UDP port ([PORT]) on the
 * host. A guest looking for games broadcasts DISCOVER; hosts answer OFFER. JOIN carries the
 * guest's build (REJECT when it differs); WELCOME carries the game to start. Then the guest
 * sends PADS each poll (its recent pads, so a lost packet costs nothing, how much of the host's
 * stream it has, and whether it wants the host's whole state again), and the host STREAMs its
 * step records from what the guest has, again each poll until acked. BYE ends it; [TIMEOUT]
 * seconds without a packet end it too.
 *
 * Thread-safe: the network thread calls [poll], the emulator thread the playing calls.
 */
class NetSession(private val build: Int, private val myName: String, private val versionName: String = "") {

    enum class Phase { IDLE, HOSTING, SEARCHING, JOINING, PLAYING, ENDED }
    enum class Reason { NONE, LEFT, TIMEOUT, VERSION, BUSY, NO_PORT, GAME }

    /** What the guest must start: the game and the exact ROM (patches) the host plays. */
    data class Start(val gameId: String, val romCrc: Int, val patches: String, val delay: Int, val title: String)
    /** A game found; [build] / [version] = the host's app (only the same build can join; -1 = older protocol). */
    data class Offer(val address: InetSocketAddress, val name: String, val title: String, var seen: Double, val id: Int,
                     val build: Int = 0, val version: String = "")
    /** The same app version as this phone (only then can it join). */
    fun compatible(o: Offer) = o.build == build

    @Volatile var phase = Phase.IDLE; private set
    @Volatile var reason = Reason.NONE; private set
    var isHost = false; private set
    @Volatile var peerName = ""; private set
    /** The game being played, set by the host's [launch]; null while both are in the hub. */
    @Volatile var start: Start? = null; private set
    /** The hub's shared state: the cartridge player 1 has selected, and whether a game runs. */
    @Volatile var hubSel = ""; private set
    @Volatile var launched = false; private set
    /** Which game of the connection the streams belong to (packets of an earlier one are dropped). */
    @Volatile var epoch = 0; private set
    private var lastHub = -1.0
    private var targets: List<InetSocketAddress> = emptyList()
    private var targetsAt = 0.0
    /** Seconds since the other side was last heard from (the "waiting" note). */
    fun silentFor(now: Double) = now - heard

    private var channel: DatagramSocket? = null
    /** Why the socket could not open (shown to the player with [Reason.NO_PORT]). */
    @Volatile var error = ""; private set
    private var session = 0
    private var peer: InetSocketAddress? = null
    private var heard = 0.0
    private var lastSearch = -1.0
    private var lastWelcome = -1.0
    private val offers = ArrayList<Offer>()

    // guest: its pads from padsFirst; host: the guest's pads by step
    private var padsFirst = -1
    private val pads = ArrayDeque<Int>()
    private val guestPads = HashMap<Int, Int>()
    private var wantState = false          // guest: ask for the host's whole state
    @Volatile var stateAsked = false       // host: the guest asked (NetPlay clears it)
    // host stream: bytes from streamBase, guest has up to acked; guest: received bytes in order
    private var stream = ByteArray(0)
    private var streamLen = 0
    private var streamBase = 0L
    private var acked = 0L
    private var received = java.io.ByteArrayOutputStream()
    private var receivedEnd = 0L
    private val ahead = java.util.TreeMap<Long, ByteArray>()

    /** Packets dropped on purpose (tests: lossy network), 0..1. */
    @Volatile var dropRate = 0.0
    private val rnd = java.util.Random(1)

    // ------------------------------------------------------------------ starting

    @Synchronized fun host(now: Double): Boolean {
        closeNow(Reason.NONE)
        isHost = true; start = null; launched = false; epoch = 0; reason = Reason.NONE
        if (!open(PORT)) { end(Reason.NO_PORT); return false }
        session = ((now * 1e6).toLong().toInt()) or 1
        heard = now
        phase = Phase.HOSTING
        log("hosting on UDP $PORT, addresses ${localAddresses().joinToString()}")
        return true
    }

    @Synchronized fun search(now: Double): Boolean {
        closeNow(Reason.NONE)
        isHost = false; reason = Reason.NONE
        if (!open(0)) { end(Reason.NO_PORT); return false }
        offers.clear(); lastSearch = -1.0; heard = now
        phase = Phase.SEARCHING
        log("searching")
        return true
    }

    @Synchronized fun offers(): List<Offer> = offers.map { it.copy() }

    /** Joins a game found by [search], or one at a known address (a Wi-Fi Direct group owner). */
    @Synchronized fun join(address: InetSocketAddress, now: Double) {
        if (channel == null && !open(0)) { end(Reason.NO_PORT); return }
        isHost = false
        peer = address; heard = now; lastWelcome = -1.0
        phase = Phase.JOINING
        log("joining $address")
    }

    @Synchronized fun close(why: Reason = Reason.LEFT) = closeNow(why)

    // ------------------------------------------------------------- the shared hub

    /** Host: the cartridge selected on its shelf (the guest's shelf follows). */
    @Synchronized fun select(gameId: String) { if (hubSel != gameId) { hubSel = gameId; lastHub = -1.0 } }   // goes out on the next poll

    /** Host: [start] runs on both phones now (a new game: fresh streams). */
    @Synchronized fun launch(start: Start) {
        epoch++; this.start = start; launched = true; hubSel = start.gameId; lastHub = -1.0
        resetGame()
        log("launch ${start.gameId} (game $epoch)")
    }

    /** Host: back in the hub; the guest leaves the game too. */
    @Synchronized fun toLobby() {
        if (!launched) return
        launched = false; start = null; lastHub = -1.0
        resetGame()
        log("back to the hub")
    }

    private fun resetGame() {
        pads.clear(); padsFirst = -1; guestPads.clear(); wantState = false; stateAsked = false
        stream = ByteArray(0); streamLen = 0; streamBase = 0; acked = 0
        received = java.io.ByteArrayOutputStream(); receivedEnd = 0; ahead.clear()
    }

    // ------------------------------------------------------------------- playing

    /** Guest: its pad for [step] (steps in order). */
    @Synchronized fun sendPad(step: Int, pad: Int) {
        if (padsFirst < 0) padsFirst = step
        if (step != padsFirst + pads.size) return
        pads.addLast(pad)
        while (pads.size > 2 * PADS_SENT) { pads.removeFirst(); padsFirst++ }
    }

    /** Guest: ask for (true) / got (false) the host's whole state. */
    @Synchronized fun askState(on: Boolean) { wantState = on }

    /** Guest: the next whole record from the host, or null. */
    @Synchronized fun takeRecord(): ByteArray? {
        val b = received.toByteArray()
        if (b.size < 4) return null
        val n = ByteBuffer.wrap(b, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (b.size < 4 + n) return null
        received = java.io.ByteArrayOutputStream().apply { write(b, 4 + n, b.size - 4 - n) }
        return b.copyOfRange(4, 4 + n)
    }

    /** Guest: whole records waiting (catch-up when behind). */
    @Synchronized fun recordsWaiting(): Int {
        val b = received.toByteArray(); var at = 0; var count = 0
        while (at + 4 <= b.size) {
            val n = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (at + 4 + n > b.size) break
            at += 4 + n; count++
        }
        return count
    }

    /** Host: the furthest step the guest's pads reached (how far ahead they come; log). */
    @Synchronized fun guestPadMax(): Int = guestPads.keys.maxOrNull() ?: -1

    /** Host: the guest's pad for [step], if it came. */
    @Synchronized fun guestPad(step: Int): Int? = guestPads[step]

    /** Host: the next step's record into the guest's stream. */
    @Synchronized fun putRecord(record: ByteArray) {
        val need = streamLen + 4 + record.size
        if (need > stream.size) stream = stream.copyOf(maxOf(need, stream.size * 2, 4096))
        ByteBuffer.wrap(stream, streamLen, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(record.size)
        System.arraycopy(record, 0, stream, streamLen + 4, record.size)
        streamLen = need
        if (guestPads.size > 4 * PADS_SENT) {
            val min = guestPads.keys.sorted().take(guestPads.size - 4 * PADS_SENT)
            min.forEach { guestPads.remove(it) }
        }
    }

    // ----------------------------------------------------------------- the wire

    /** Reads every waiting packet, sends what is due, ends a silent session. */
    @Synchronized fun poll(now: Double) {
        val ch = channel ?: return
        val buf = ByteArray(2048)
        for (i in 0 until 64) {
            val pk = DatagramPacket(buf, buf.size)
            try { ch.receive(pk) } catch (e: SocketTimeoutException) { break } catch (e: Exception) { break }
            receive(pk.socketAddress as InetSocketAddress, buf.copyOf(pk.length), now)
            if (channel == null) return
        }
        when (phase) {
            Phase.SEARCHING -> {
                if (lastSearch < 0 || now - lastSearch >= SEARCH_EVERY) {
                    lastSearch = now
                    // the network's broadcast addresses, read again every few seconds (slow on old phones)
                    if (targets.isEmpty() || now - targetsAt > 5.0) { targets = broadcastTargets(); targetsAt = now }
                    for (a in targets) send(DISCOVER, ByteArray(0), a)
                }
                offers.removeAll { now - it.seen > OFFER_LIFE }
            }
            Phase.JOINING -> {
                if (lastWelcome < 0 || now - lastWelcome >= WELCOME_EVERY) {
                    lastWelcome = now
                    val o = Out(); o.i32(build); o.text(myName)
                    send(JOIN, o.bytes(), peer!!)
                }
                if (now - heard > TIMEOUT) { log("no answer from $peer"); end(Reason.TIMEOUT) }
            }
            Phase.PLAYING -> {
                val p = peer!!
                if (isHost && (lastHub < 0 || now - lastHub >= HUB_EVERY)) {
                    // The hub's state, again and again: also what keeps the guest hearing us.
                    lastHub = now
                    val o = Out(); o.i32(epoch); o.text(hubSel); o.u8(if (launched) 1 else 0)
                    start?.let { st -> o.text(st.gameId); o.i32(st.romCrc); o.text(st.patches); o.u8(st.delay); o.text(st.title) }
                    send(HUB, o.bytes(), p)
                }
                if (isHost && launched) {
                    // Everything the guest has not acknowledged, again each poll (a LAN loses
                    // little and the stream is small); an empty chunk keeps the guest hearing us.
                    val end = streamBase + streamLen
                    var at = acked
                    var chunks = 0
                    do {
                        val n = minOf(CHUNK.toLong(), end - at).toInt()
                        val o = Out(); o.i32(epoch); o.i64(at); o.i32(n)
                        val from = (at - streamBase).toInt()
                        o.raw(stream, from, n)
                        send(STREAM, o.bytes(), p)
                        at += n; chunks++
                    } while (at < end && chunks < CHUNKS_PER_POLL)
                } else if (!isHost) {
                    val count = minOf(pads.size, PADS_SENT)
                    val first = padsFirst + pads.size - count
                    val o = Out(); o.i32(epoch); o.i64(receivedEnd); o.i32(first); o.u8(count); o.u8(if (wantState) 1 else 0)
                    for (i in 0 until count) o.i32(pads[pads.size - count + i])
                    send(PADS, o.bytes(), p)
                }
                if (now - heard > TIMEOUT) end(Reason.TIMEOUT)
            }
            else -> {}
        }
    }

    private fun receive(from: InetSocketAddress, packet: ByteArray, now: Double) {
        if (dropRate > 0 && rnd.nextDouble() < dropRate) return
        val r = In(packet)
        if (r.i32() != MAGIC || !r.ok) return
        val ver = r.u8()
        val type = r.u8()
        val sess = r.i32()
        if (!r.ok) return
        if (ver != VERSION) {
            // Another protocol (another app version): listed so the player sees why, never joined.
            if (type == OFFER && !isHost && phase == Phase.SEARCHING && offers.none { it.id == sess }) {
                offers += Offer(from, r.text(), "", now, sess, -1, "starsza")
            }
            offers.firstOrNull { it.id == sess }?.seen = now
            return
        }
        when (type) {
            DISCOVER -> if (isHost && phase == Phase.HOSTING) {
                val o = Out(); o.text(myName); o.text(""); o.i32(build); o.text(versionName)
                send(OFFER, o.bytes(), from)
            }
            OFFER -> if (!isHost && phase == Phase.SEARCHING) {
                val name = r.text(); val title = r.text(); val b = r.i32(); val v = r.text()
                if (!r.ok) return
                val o = offers.firstOrNull { it.id == sess }
                if (o == null) { offers += Offer(from, name, title, now, sess, b, v); log("game of $name ($v, build $b) at $from") }
                else o.seen = now
            }
            JOIN -> {
                if (!isHost) return
                val b = r.i32(); val name = r.text()
                if (!r.ok) return
                if (phase == Phase.PLAYING && from != peer) { send(REJECT, byteArrayOf(Reason.BUSY.ordinal.toByte()), from); return }
                if (phase != Phase.HOSTING && phase != Phase.PLAYING) return
                if (b != build) { send(REJECT, byteArrayOf(Reason.VERSION.ordinal.toByte()), from); log("JOIN from $name refused: build $b, ours $build"); return }
                if (phase == Phase.HOSTING) {
                    peer = from; peerName = name; heard = now; phase = Phase.PLAYING
                    log("JOIN from $name at $from: welcome")
                }
                val o = Out(); o.text(myName)
                send(WELCOME, o.bytes(), from)
                lastHub = -1.0
            }
            WELCOME -> if (!isHost && from == peer && phase == Phase.JOINING) {
                val name = r.text()
                if (!r.ok) return
                peerName = name; session = sess; heard = now
                start = null; launched = false; epoch = 0
                phase = Phase.PLAYING
                log("WELCOME from $name")
            }
            HUB -> if (!isHost && from == peer && sess == session && phase == Phase.PLAYING) {
                val e = r.i32(); val sel = r.text(); val on = r.u8() != 0
                val st = if (on) Start(r.text(), r.i32(), r.text(), r.u8(), r.text()) else null
                if (!r.ok) return
                heard = now
                hubSel = sel
                if (on && e != epoch) { epoch = e; resetGame(); start = st; launched = true; log("host started ${st?.gameId} (game $e)") }
                else if (!on && launched) { epoch = e; resetGame(); start = null; launched = false; log("host back in the hub") }
            }
            REJECT -> if (!isHost && from == peer && phase == Phase.JOINING) {
                val why = r.u8()
                end(Reason.values().getOrElse(why) { Reason.LEFT })
            }
            PADS -> {
                if (!isHost || phase != Phase.PLAYING || from != peer || sess != session) return
                val e = r.i32()
                heard = now
                if (e != epoch || !launched) return
                val ack = r.i64(); val first = r.i32(); val count = r.u8(); val want = r.u8()
                val got = IntArray(count) { r.i32() }
                if (!r.ok) return
                heard = now
                for (i in 0 until count) guestPads.putIfAbsent(first + i, got[i])
                if (want != 0) stateAsked = true
                val end = streamBase + streamLen
                if (ack in (acked + 1)..end) {
                    acked = ack
                    // The guest has it all up to here: keep only what may be sent again.
                    val drop = (acked - streamBase).toInt()
                    if (drop > 64 * 1024) {
                        System.arraycopy(stream, drop, stream, 0, streamLen - drop)
                        streamLen -= drop; streamBase = acked
                    }
                }
            }
            STREAM -> {
                if (isHost || phase != Phase.PLAYING || from != peer || sess != session) return
                if (r.i32() != epoch || !launched) return
                val offset = r.i64(); val n = r.i32(); val bytes = r.raw(n)
                if (!r.ok) return
                heard = now
                if (n == 0) return
                if (offset > receivedEnd) { if (ahead.size < 256) ahead[offset] = bytes; return }
                fun take(at: Long, b: ByteArray) {
                    if (at + b.size <= receivedEnd) return
                    val skip = (receivedEnd - at).toInt()
                    received.write(b, skip, b.size - skip)
                    receivedEnd = at + b.size
                }
                take(offset, bytes)
                while (ahead.isNotEmpty() && ahead.firstKey() <= receivedEnd) {
                    val e = ahead.pollFirstEntry()!!; take(e.key, e.value)
                }
            }
            BYE -> if (from == peer && sess == session && (phase == Phase.PLAYING || phase == Phase.JOINING)) {
                log("BYE from $from"); end(Reason.LEFT)
            }
        }
    }

    private fun send(type: Int, body: ByteArray, to: InetSocketAddress) {
        val ch = channel ?: return
        val o = Out(); o.i32(MAGIC); o.u8(VERSION); o.u8(type); o.i32(session); o.raw(body, 0, body.size)
        val b = o.bytes()
        try { ch.send(DatagramPacket(b, b.size, to)) } catch (e: Exception) { /* unreachable address: next poll */ }
    }

    /** Opened on a thread of its own: Android refuses network calls on the UI thread. */
    private fun open(port: Int): Boolean {
        var made: DatagramSocket? = null
        var failure: Throwable? = null
        val t = Thread {
            try {
                made = DatagramSocket(null).apply {
                    reuseAddress = false; broadcast = true
                    bind(InetSocketAddress(port))
                    soTimeout = 1          // poll(): a receive with nothing waiting returns at once
                }
            } catch (e: Throwable) { failure = e }
        }
        t.start(); t.join(3000)
        channel = made
        if (made == null) {
            error = failure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "brak odpowiedzi"
            log("UDP port $port: $error")
            return false
        }
        error = ""
        return true
    }

    private fun closeNow(why: Reason) {
        val p = peer
        if (channel != null && p != null && (phase == Phase.PLAYING || phase == Phase.JOINING)) {
            repeat(3) { send(BYE, byteArrayOf(why.ordinal.toByte()), p) }
        }
        runCatching { channel?.close() }
        channel = null
        if (phase != Phase.IDLE && phase != Phase.ENDED) { phase = Phase.ENDED; if (reason == Reason.NONE) reason = why }
        if (why == Reason.NONE) phase = Phase.IDLE
        peer = null; peerName = ""; offers.clear()
        pads.clear(); padsFirst = -1; guestPads.clear(); wantState = false; stateAsked = false
        stream = ByteArray(0); streamLen = 0; streamBase = 0; acked = 0
        received = java.io.ByteArrayOutputStream(); receivedEnd = 0; ahead.clear()
    }

    private fun end(why: Reason) { reason = why; closeNow(why); phase = Phase.ENDED; reason = why }

    var logger: ((String) -> Unit)? = null
    private fun log(s: String) { logger?.invoke("net: $s") }

    // --------------------------------------------------------------- byte helpers

    private class Out {
        private val b = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = b.write(v and 0xFF)
        fun i32(v: Int) { for (i in 0 until 4) b.write(v ushr (8 * i) and 0xFF) }
        fun i64(v: Long) { i32(v.toInt()); i32((v ushr 32).toInt()) }
        fun raw(a: ByteArray, from: Int, n: Int) = b.write(a, from, n)
        fun text(s: String) { val t = s.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }; u8(t.size); raw(t, 0, t.size) }
        fun bytes(): ByteArray = b.toByteArray()
    }

    private class In(private val d: ByteArray) {
        var at = 0; var ok = true
        private fun has(n: Int): Boolean { if (n < 0 || at + n > d.size) ok = false; return ok }
        fun u8(): Int = if (has(1)) d[at++].toInt() and 0xFF else 0
        fun i32(): Int { if (!has(4)) return 0; var v = 0; for (i in 0 until 4) v = v or ((d[at + i].toInt() and 0xFF) shl (8 * i)); at += 4; return v }
        fun i64(): Long { val lo = i32().toLong() and 0xFFFFFFFFL; return lo or (i32().toLong() shl 32) }
        fun raw(n: Int): ByteArray { if (!has(n)) return ByteArray(0); return d.copyOfRange(at, at + n).also { at += n } }
        fun text(): String = String(raw(u8()), Charsets.UTF_8)
    }

    companion object {
        const val PORT = 27031                 // t3native uses 27030
        const val MAGIC = 0x4853454E           // "NESH"
        const val VERSION = 2
        const val TIMEOUT = 15.0
        const val OFFER_LIFE = 3.0
        const val SEARCH_EVERY = 0.5
        const val WELCOME_EVERY = 0.2
        const val PADS_SENT = 32
        const val CHUNK = 1024
        const val CHUNKS_PER_POLL = 8
        const val DISCOVER = 1; const val OFFER = 2; const val JOIN = 3; const val WELCOME = 4
        const val REJECT = 5; const val PADS = 6; const val STREAM = 7; const val BYE = 8; const val HUB = 9
        const val HUB_EVERY = 0.1

        /** This device's IPv4 addresses on networks with broadcast (not mobile data). */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }.filter { it.address.address.size == 4 && it.broadcast != null }
                .map { it.address.hostAddress ?: "" }
        }.getOrDefault(emptyList())

        /** The subnet broadcasts, the all-ones broadcast and this device itself (two copies on one phone). */
        fun broadcastTargets(): List<InetSocketAddress> {
            val t = LinkedHashSet<InetSocketAddress>()
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) ia.broadcast?.let { t += InetSocketAddress(it, PORT) }
                }
            }
            t += InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT)
            t += InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT)
            return t.toList()
        }
    }
}
