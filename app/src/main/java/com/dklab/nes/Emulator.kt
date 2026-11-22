package com.dklab.nes

import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * The Kotlin side of libgbcplayer.so.
 *
 * The native layer (app/src/main/cpp/gbcplayer.c) exports these ten methods both as
 * plain `Java_com_dklab_nes_NativeBridge_*` symbols and, from JNI_OnLoad, via
 * RegisterNatives against `com/dklab/nes/NativeBridge`. Both routes name this
 * exact class in this exact package, so DO NOT rename or move it, and do not change a
 * signature without changing kMethods[] in gbcplayer.c to match. RegisterNatives binds
 * the whole table or none of it: an extra or missing declaration here silently falls
 * back to symbol lookup, which still works, but a *renamed* one does not.
 *
 * `object`, not a companion: Kotlin then emits these as instance methods on the
 * singleton, which is what both binding routes expect.
 */
object NativeBridge {

    init {
        // Prebuilt by tools/build_native.sh, packaged from app/src/main/jniLibs.
        // Gradle does not compile it; it only zips it into the APK.
        System.loadLibrary("gbcplayer")
    }

    /** Returns an opaque handle, or 0 on failure (the reason goes to logcat tag "gbcplayer"). */
    external fun nativeInit(rom: ByteArray, sampleRate: Int): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeSetButtons(handle: Long, mask: Int)

    /**
     * Runs exactly one video frame (70224 ticks). Fills [argbOut] with 160*144 pixels in
     * 0xAARRGGBB order and [pcmOut] with signed 16-bit stereo interleaved samples.
     * Either may be null. Returns the number of *stereo frames* written to [pcmOut] --
     * roughly 738 at 44100 Hz, but never assume a fixed count: it is 0 while the game
     * has the APU switched off.
     */
    external fun nativeRunFrame(handle: Long, argbOut: IntArray?, pcmOut: ShortArray?): Int

    /** Direct 160*144*4 view of the core's frame buffer, R,G,B,A byte order. Unused by the
     *  loop (the IntArray path above is the one the native agent pixel-verified), kept
     *  because RegisterNatives binds the table as a unit. */
    external fun nativeGetFrameBuffer(handle: Long): ByteBuffer?

    /** Battery-backed cartridge RAM, or null when the cartridge has no battery. */
    external fun nativeGetSram(handle: Long): ByteArray?

    /** Fails unless [sram] is exactly the size this cartridge wants. */
    external fun nativeSetSram(handle: Long, sram: ByteArray): Boolean

    /** True once since the previous call if the game wrote to cartridge RAM. Reading it
     *  clears the flag, so exactly one caller may poll it. */
    external fun nativeIsSramDirty(handle: Long): Boolean

    external fun nativeSaveState(handle: Long): ByteArray?

    external fun nativeLoadState(handle: Long, state: ByteArray): Boolean

    /** Cumulative frames in which the game read the joypad (non-slowdown frames). */
    external fun nativeGameFrames(handle: Long): Long

    /** Power cycle: a fresh game. */
    external fun nativeReset(handle: Long)

    /** The game's modifications (games/<name>.c): count, "key\ttype\tmin\tmax\tpersist\tlabel\tcaption", value. */
    external fun nativeModCount(): Int
    external fun nativeModInfo(i: Int): String?
    external fun nativeModGet(i: Int): Int
    external fun nativeModSet(i: Int, value: Int)
    /** Hub build: select the game plug-in by id before nativeInit; false = unknown id. */
    external fun nativeSetGame(id: String): Boolean
    /** Hub build: select the game plug-in matching this ROM; its id, or "" = none (generic). */
    external fun nativeDetectGame(rom: ByteArray): String
    /** Netplay: FNV-1a of the work RAM, compared by both phones. */
    external fun nativeRamHash(handle: Long): Int
    /** A core option the player picks (palette, NTSC filter); applied on the next frame. */
    external fun nativeSetOption(key: String, value: String)
    /** NTSC filter on: the whole filtered frame (width x 224) into [out]; returns the width, 0 = off. */
    external fun nativeWideFrame(handle: Long, out: IntArray?): Int

    /** Controller 2 (player 2), same bits as nativeSetButtons. */
    external fun nativeSetButtons2(handle: Long, mask: Int)
    /** Zapper aim in frame pixels (outside = pointing away) and trigger. */
    external fun nativeSetPointer(handle: Long, x: Int, y: Int, pressed: Boolean)
    /** True when the loaded game's plug-in puts a Zapper on port 2. */
    external fun nativeUsesZapper(): Boolean
    /** While on, each runFrame steps ~2 frames back in time (silent). */
    external fun nativeSetRewind(handle: Long, on: Boolean)
}

/**
 * One running Game Boy. Owns a native handle and frees it exactly once.
 *
 * Every call is taken under [lock]. The emulator thread is the only caller in the hot
 * path, so the monitor is always uncontended there (tens of nanoseconds against a frame
 * that costs about a millisecond); the lock exists so that [close] from the UI thread
 * can never race a frame that is already inside the core.
 */
class Emulator private constructor(private var handle: Long, val sampleRate: Int) : Closeable {

    private val lock = Any()

    /** False once [close] has run. */
    val isOpen: Boolean get() = synchronized(lock) { handle != 0L }

    fun setButtons(mask: Int) = synchronized(lock) {
        if (handle != 0L) NativeBridge.nativeSetButtons(handle, mask)
    }

    /** @return stereo frames written into [pcm]. */
    fun runFrame(argb: IntArray?, pcm: ShortArray?): Int = synchronized(lock) {
        if (handle == 0L) 0 else NativeBridge.nativeRunFrame(handle, argb, pcm)
    }

    fun gameFrames(): Long = synchronized(lock) {
        if (handle == 0L) 0L else NativeBridge.nativeGameFrames(handle)
    }

    fun reset() = synchronized(lock) { if (handle != 0L) NativeBridge.nativeReset(handle) }

    /** One modification of the loaded game (see game.h). */
    class Mod(val index: Int, val key: String, val type: Int, val min: Int, val max: Int,
              val persist: Boolean, val label: String, val caption: String)

    fun mods(): List<Mod> = synchronized(lock) {
        (0 until NativeBridge.nativeModCount()).mapNotNull { i ->
            val f = NativeBridge.nativeModInfo(i)?.split("\t") ?: return@mapNotNull null
            if (f.size < 7) null
            else Mod(i, f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4] == "1", f[5], f[6])
        }
    }
    fun modGet(i: Int): Int = synchronized(lock) { NativeBridge.nativeModGet(i) }
    fun modSet(i: Int, v: Int) = synchronized(lock) { NativeBridge.nativeModSet(i, v) }

    fun setButtons2(mask: Int) = synchronized(lock) { if (handle != 0L) NativeBridge.nativeSetButtons2(handle, mask) }
    fun setPointer(x: Int, y: Int, pressed: Boolean) = synchronized(lock) {
        if (handle != 0L) NativeBridge.nativeSetPointer(handle, x, y, pressed)
    }
    fun usesZapper(): Boolean = NativeBridge.nativeUsesZapper()
    fun wideFrame(out: IntArray): Int = synchronized(lock) { if (handle != 0L) NativeBridge.nativeWideFrame(handle, out) else 0 }
    fun ramHash(): Int = synchronized(lock) { if (handle != 0L) NativeBridge.nativeRamHash(handle) else 0 }
    fun setRewind(on: Boolean) = synchronized(lock) { if (handle != 0L) NativeBridge.nativeSetRewind(handle, on) }

    fun readSram(): ByteArray? = synchronized(lock) {
        if (handle == 0L) null else NativeBridge.nativeGetSram(handle)
    }

    fun writeSram(sram: ByteArray): Boolean = synchronized(lock) {
        handle != 0L && NativeBridge.nativeSetSram(handle, sram)
    }

    fun isSramDirty(): Boolean = synchronized(lock) {
        handle != 0L && NativeBridge.nativeIsSramDirty(handle)
    }

    fun saveState(): ByteArray? = synchronized(lock) {
        if (handle == 0L) null else NativeBridge.nativeSaveState(handle)
    }

    fun loadState(state: ByteArray): Boolean = synchronized(lock) {
        handle != 0L && NativeBridge.nativeLoadState(handle, state)
    }

    /**
     * Frees the core. Idempotent and safe from any thread: the handle is taken and
     * zeroed under the lock, so a second call finds 0 and does nothing, and a frame
     * running concurrently either finished before the swap or sees 0 and returns.
     */
    override fun close() {
        val doomed = synchronized(lock) {
            val h = handle
            handle = 0L
            h
        }
        if (doomed != 0L) NativeBridge.nativeDestroy(doomed)
    }

    companion object {
        const val SCREEN_WIDTH = 256
        const val SCREEN_HEIGHT = 224
        const val SCREEN_PIXELS = SCREEN_WIDTH * SCREEN_HEIGHT

        /** Ticks in one video frame, and the DMG/CGB tick rate. */
        const val TICKS_PER_FRAME = 70224L
        const val CPU_TICKS_PER_SECOND = 4194304L

        /** 70224 / 4194304 s = 16.742706 ms, i.e. 59.7275 fps. Not 16 ms. */
        const val FRAME_NANOS = 16_639_267L // NTSC NES: 60.0988 fps

        /** AudioTrack rate. 44100 is the one rate every Android device resamples least. */
        const val SAMPLE_RATE = 48000

        /** binjgb's MAXIMUM_ROM_SIZE; gbc_open() rejects anything larger. */
        const val MAX_ROM_BYTES = 8 * 1024 * 1024

        // Button bits, matching joypad_pack_buttons() in binjgb and GBC_BUTTON_* in
        // gbcplayer.h. The on-screen controls and the hardware keymap both use these.
        const val BTN_A = 1 shl 0
        const val BTN_B = 1 shl 1
        const val BTN_SELECT = 1 shl 2
        const val BTN_START = 1 shl 3
        const val BTN_RIGHT = 1 shl 4
        const val BTN_LEFT = 1 shl 5
        const val BTN_UP = 1 shl 6
        const val BTN_DOWN = 1 shl 7

        private const val TAG = "gbcplayer"

        /**
         * Opens [rom]. Returns null instead of throwing when the core refuses it -- a
         * truncated download or a file that is not a Game Boy ROM is a normal thing for a
         * user to pick, not an exceptional one. A missing or unloadable .so surfaces here
         * too, as UnsatisfiedLinkError wrapped in ExceptionInInitializerError.
         */
        fun open(rom: ByteArray, sampleRate: Int = SAMPLE_RATE): Emulator? {
            if (rom.size > MAX_ROM_BYTES) {
                Log.e(TAG, "rom is ${rom.size} bytes, the core accepts at most $MAX_ROM_BYTES")
                return null
            }
            val handle = try {
                NativeBridge.nativeInit(rom, sampleRate)
            } catch (t: Throwable) {
                Log.e(TAG, "native library unavailable", t)
                return null
            }
            if (handle == 0L) {
                Log.e(TAG, "core rejected the rom (${rom.size} bytes)")
                return null
            }
            return Emulator(handle, sampleRate)
        }
    }
}
