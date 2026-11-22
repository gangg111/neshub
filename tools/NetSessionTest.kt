// Netplay on a PC: NetSession + NetPlay, host and guest in one process over UDP on this
// machine, with a fake deterministic "emulator". Checks discovery, join, the lockstep
// (guest's state = host's at every step), a lossy network, the host's state jumping
// (a slot loaded) and a guest that drifts apart (resync by the RAM hash).
//   kotlinc tools/NetSessionTest.kt app/src/main/java/com/dklab/nes/NetSession.kt \
//     app/src/main/java/com/dklab/nes/NetPlay.kt -include-runtime -d /tmp/nt.jar && java -jar /tmp/nt.jar
import com.dklab.nes.NetPlay
import com.dklab.nes.NetSession
import java.nio.ByteBuffer

class Fake(val who: String) : NetPlay.Machine {
    var s = 1L; var frames = 0; var mod = 0
    val seenP2 = ArrayList<Int>()
    override fun run(pad1: Int, pad2: Int) {
        s = s * 6364136223846793005L + 1442695040888963407L + pad1 * 31L + pad2 * 1009L + mod
        frames++; seenP2 += pad2
    }
    override fun save(): ByteArray = ByteBuffer.allocate(20000).putLong(s).putInt(mod).array()   // state-sized
    override fun load(state: ByteArray): Boolean { val b = ByteBuffer.wrap(state); s = b.long; mod = b.int; return true }
    override fun hash(): Int = (s xor (s ushr 32)).toInt()
    override fun mods(): IntArray = intArrayOf(mod)
    override fun setMods(values: IntArray) { if (values.isNotEmpty()) mod = values[0] }
}

fun main() {
    val t0 = System.nanoTime()
    fun now() = (System.nanoTime() - t0) / 1e9
    val host = NetSession(build = 7, myName = "HOST")
    val guest = NetSession(build = 7, myName = "GUEST")
    check(host.host(now())) { "host failed" }
    check(guest.search(now()))
    // discovery
    var offer: NetSession.Offer? = null
    for (i in 0 until 400) { host.poll(now()); guest.poll(now()); offer = guest.offers().firstOrNull(); if (offer != null) break; Thread.sleep(5) }
    println("offer: ${offer?.name} ${offer?.title} ${offer?.address}")
    check(offer != null) { "no offer" }
    guest.join(offer.address, now())
    for (i in 0 until 400) { host.poll(now()); guest.poll(now()); if (guest.phase == NetSession.Phase.PLAYING && host.phase == NetSession.Phase.PLAYING) break; Thread.sleep(5) }
    println("joined: host ${host.phase} peer=${host.peerName}, guest ${guest.phase} launched=${guest.launched}")
    // the shared hub: player 1 browses, the guest's shelf follows; then player 1 starts a game
    host.select("tinytoon")
    for (i in 0 until 200) { host.poll(now()); guest.poll(now()); if (guest.hubSel == "tinytoon") break; Thread.sleep(5) }
    println("guest's shelf follows: ${guest.hubSel}")
    check(guest.hubSel == "tinytoon")
    host.launch(NetSession.Start("contra", 0x1234, "", 3, "Contra"))
    for (i in 0 until 200) { host.poll(now()); guest.poll(now()); if (guest.launched) break; Thread.sleep(5) }
    println("guest starts: ${guest.start}")
    check(guest.start?.gameId == "contra")

    host.dropRate = 0.2; guest.dropRate = 0.2          // lossy network, both ways
    val hm = Fake("host"); val gm = Fake("guest")
    gm.s = 999                                         // the guest starts elsewhere: the first state fixes it
    val hp = NetPlay(host, hm, 3); val gp = NetPlay(guest, gm, 3)
    val hostHashes = HashMap<Int, Long>()
    val rnd = java.util.Random(5)
    val guestPads = HashMap<Int, Int>()
    var hostSteps = 0; var guestSteps = 0; var mismatches = 0
    val end = 6000
    var tick = 0
    while (gp.step < end && tick < 200000) {
        tick++
        host.poll(now()); guest.poll(now())
        if (hp.step < end && hp.hostStep(rnd.nextInt(256))) { hostSteps++; hostHashes[hp.step] = hm.s }
        if (hp.step == 2000 && hm.mod == 0) { hm.s = 42; hm.mod = 5; hp.hostWantsState = true }   // slot loaded + mod
        val gpad = (gp.step * 7) and 0xFF
        val before = gp.step
        if (gp.guestStep(gpad)) {
            guestSteps++; guestPads[before + 3] = gpad
            if (hostHashes[gp.step] != gm.s) mismatches++
        }
        if (gp.step == 4000 && gm.frames % 1 == 0 && gm.mod == 5 && !forced) { gm.s += 1; forced = true }  // drift
        if (tick % 4 == 0) Thread.sleep(1)
    }
    // the guest's pads reached the host's game as player 2, each at its step
    val p2ok = (10 until hostSteps).count { hm.seenP2[it] == (guestPads[it] ?: -1) }
    println("steps host=$hostSteps guest=$guestSteps ticks=$tick, guest state != host after a step: $mismatches times, " +
        "resyncs=${gp.resyncs}, host waits=${hp.stalls}, P2 pads right ${p2ok}/${hostSteps - 10}")
    println("final: host ${hm.s} mod ${hm.mod}, guest ${gm.s} mod ${gm.mod} -> ${if (hostHashes[gp.step] == gm.s && gm.mod == hm.mod) "SAME" else "DIFFERENT"}")
    // back to the hub and a second game on the same connection (fresh streams, no leftovers)
    host.toLobby()
    for (i in 0 until 200) { host.poll(now()); guest.poll(now()); if (!guest.launched) break; Thread.sleep(5) }
    println("guest back in the hub: launched=${guest.launched}")
    host.launch(NetSession.Start("chipndale", 0x99, "", 3, "Chip"))
    for (i in 0 until 200) { host.poll(now()); guest.poll(now()); if (guest.launched) break; Thread.sleep(5) }
    val hm2 = Fake("h2"); val gm2 = Fake("g2"); gm2.s = 7
    val hp2 = NetPlay(host, hm2, 3); val gp2 = NetPlay(guest, gm2, 3)
    val h2 = HashMap<Int, Long>(); var bad2 = 0; var t2 = 0
    while (gp2.step < 1500 && t2 < 100000) {
        t2++; host.poll(now()); guest.poll(now())
        if (hp2.step < 1500 && hp2.hostStep(t2 and 0xFF)) h2[hp2.step] = hm2.s
        if (gp2.guestStep(1) && h2[gp2.step] != gm2.s) bad2++
        if (t2 % 4 == 0) Thread.sleep(1)
    }
    println("second game ${guest.start?.gameId}: guest steps ${gp2.step}, differing $bad2, final ${if (h2[gp2.step] == gm2.s) "SAME" else "DIFFERENT"}")
    host.close(); guest.close()
    for (i in 0 until 50) { host.poll(now()); guest.poll(now()); Thread.sleep(2) }
    println("after close: host ${host.phase}/${host.reason}, guest ${guest.phase}/${guest.reason}")
    // another app version: listed but not compatible, and the host refuses the JOIN
    val h3 = NetSession(build = 9, myName = "NEW", versionName = "3.0"); val g3 = NetSession(build = 8, myName = "OLD", versionName = "2.9")
    h3.host(now()); g3.search(now())
    var o3: NetSession.Offer? = null
    for (i in 0 until 400) { h3.poll(now()); g3.poll(now()); o3 = g3.offers().firstOrNull(); if (o3 != null) break; Thread.sleep(5) }
    println("other version: offer ${o3?.name} ${o3?.version} compatible=${o3?.let { g3.compatible(it) }}")
    g3.join(o3!!.address, now())
    for (i in 0 until 400) { h3.poll(now()); g3.poll(now()); if (g3.phase == NetSession.Phase.ENDED) break; Thread.sleep(5) }
    println("join refused: guest ${g3.phase}/${g3.reason}, host ${h3.phase}")
    h3.close(); g3.close()
}
var forced = false
