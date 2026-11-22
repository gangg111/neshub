package com.dklab.nes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Netplay's lockstep over a [NetSession] (plain JVM; tools/NetSessionTest.kt drives it).
 *
 * Both phones run the same emulator from the same state with the same pads, and the NES is
 * deterministic (tools/nettest.c: another process loading the state and replaying the pads
 * has the same RAM every frame, all games). The host plays step s with its own pad and the
 * guest's pad for s, and sends a record of it; the guest plays the records. The guest's pad
 * for s is sent [NetSession.Start.delay] steps ahead, so the host rarely waits for it.
 *
 * Record: step u32, pad1 u16, pad2 u16, flags u8 (1 = RAM hash after the step follows,
 * 2 = the host's whole state before the step and the mods' values follow).
 * The whole state goes at the start, whenever the host's game jumps (a slot loaded, reset,
 * a mod changed: [hostWantsState]) and when the guest's RAM hash differs from the host's
 * (the guest then asks for it).
 */
class NetPlay(private val session: NetSession, private val machine: Machine, val delay: Int) {

    interface Machine {
        fun run(pad1: Int, pad2: Int)
        fun save(): ByteArray?
        fun load(state: ByteArray): Boolean
        fun hash(): Int
        /** The mods' values the state does not hold (toggles applied as cheat codes). */
        fun mods(): IntArray
        fun setMods(values: IntArray)
    }

    var step = 0; private set
    /** Host: send the whole state with the next step. */
    @Volatile var hostWantsState = true
    private var lastStateStep = -1000
    @Volatile var resyncs = 0; private set
    @Volatile var stalls = 0L; private set
    private var guestStarted = false
    private var awaitingState = false

    /** Host: one step if the guest's pad for it is here; false = waiting for the guest. */
    fun hostStep(pad1: Int): Boolean {
        val pad2 = session.guestPad(step) ?: run { stalls++; return false }
        if (session.stateAsked && step - lastStateStep > STATE_GAP) { hostWantsState = true }
        session.stateAsked = false
        var flags = 0
        var state: ByteArray? = null
        if (hostWantsState) {
            state = machine.save()
            if (state != null) { flags = flags or 2; lastStateStep = step }
            hostWantsState = false
        }
        machine.run(pad1, pad2)
        val withHash = step % HASH_EVERY == 0
        if (withHash) flags = flags or 1
        val mods = if (state != null) machine.mods() else IntArray(0)
        val b = ByteBuffer.allocate(9 + (if (withHash) 4 else 0) +
            (if (state != null) 4 + state.size + 1 + 4 * mods.size else 0)).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(step); b.putShort(pad1.toShort()); b.putShort(pad2.toShort()); b.put(flags.toByte())
        if (withHash) b.putInt(machine.hash())
        if (state != null) {
            b.putInt(state.size); b.put(state)
            b.put(mods.size.toByte()); mods.forEach { b.putInt(it) }
        }
        session.putRecord(b.array())
        step++
        return true
    }

    /** Guest: plays the next record if it came (false = waiting for the host). */
    /** Guest: its first [delay] pads (empty), so the host can begin; called before any record is played. */
    fun guestBegin() {
        if (!guestStarted) { for (s in 0 until delay) session.sendPad(s, 0); guestStarted = true; awaitingState = true }
    }

    fun guestStep(localPad: Int): Boolean {
        guestBegin()
        val rec = session.takeRecord() ?: run { stalls++; return false }
        val b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        val s = b.int; val pad1 = b.short.toInt() and 0xFFFF; val pad2 = b.short.toInt() and 0xFFFF
        val flags = b.get().toInt()
        val hash = if (flags and 1 != 0) b.int else 0
        if (flags and 2 != 0) {
            val state = ByteArray(b.int).also { b.get(it) }
            val mods = IntArray(b.get().toInt() and 0xFF) { b.int }
            machine.load(state); machine.setMods(mods)
            session.askState(false); awaitingState = false
        }
        step = s
        machine.run(pad1, pad2)
        if (flags and 1 != 0 && !awaitingState && machine.hash() != hash) {
            resyncs++; awaitingState = true; session.askState(true)
        }
        session.sendPad(s + delay, localPad)
        step = s + 1
        return true
    }

    /** Guest: records already here (more than a couple: play faster to catch up). */
    fun backlog() = session.recordsWaiting()

    companion object {
        const val HASH_EVERY = 30
        const val STATE_GAP = 60      // a resync asked for again within this many steps is the same one
    }
}
