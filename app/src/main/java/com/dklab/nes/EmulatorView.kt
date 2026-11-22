package com.dklab.nes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min

/**
 * The Game Boy screen, and the loop that drives it.
 *
 * Video, audio and timing all live on one dedicated thread ([EmuThread]) -- not the UI
 * thread, and not a Choreographer callback. The pacer is the AudioTrack: writing in
 * MODE_STREAM with WRITE_BLOCKING returns only when the mixer has consumed a frame's
 * worth of samples, so the loop advances at exactly the rate the audio hardware plays,
 * which is the only clock that keeps picture and sound together over a long session. A
 * wall clock is kept alongside it purely as a backstop, because a game that switches its
 * APU off produces no samples at all and would otherwise let the loop free-run.
 *
 * The view is public API for two other agents:
 *   - the controls overlay calls [setButton] / [setButtons] with Emulator.BTN_* bits;
 *   - the activity calls [loadRom], [onActivityResume], [onActivityPause], [release].
 */
class EmulatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    // ---- rendering ---------------------------------------------------------------
    // One bitmap, one pixel array, one paint, one rect. Nothing is allocated per frame.
    private val bitmap =
        Bitmap.createBitmap(Emulator.SCREEN_WIDTH, Emulator.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
            // gbc_run_frame() ORs 0xff000000 into every pixel, so the alpha channel is a
            // constant. Saying so lets Skia use the opaque blit instead of blending 23040
            // pixels against the surface sixty times a second.
            .apply { setHasAlpha(false) }
    private val pixels = IntArray(Emulator.SCREEN_PIXELS)
    private val paint = Paint().apply {
        // A Game Boy screen must stay a grid of hard squares. Bilinear filtering turns it
        // into mush, and dithering invents colours the hardware never produced.
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }
    private val destRect = Rect()
    /** Part of the frame that is drawn: whole frame, or minus the 8 px columns at each
     *  side when filling (the game blanks its leftmost 8 px to hide scroll updates). */
    private val srcRect = Rect(0, 0, Emulator.SCREEN_WIDTH, Emulator.SCREEN_HEIGHT)
    private val crtPaint = Paint().apply { color = 0x5A000000 }
    /** The CRT picture on the GPU (Android 13+); older phones keep the plain scanlines. */
    private val crtShader: CrtShader? by lazy { CrtShader.create(bitmap) }
    private var crtPlacedFor = Rect()
    private var crtBoundTo: Bitmap? = null
    private var destForWidth = -1
    private var destForHeight = -1
    private var letterboxed = false

    // ---- audio -------------------------------------------------------------------
    // sampleRate/50 stereo frames would just fit one video frame; four times that is
    // still only 17 kB and means a long frame can never be clipped by the core.
    private val pcm = ShortArray(Emulator.SAMPLE_RATE / 50 * 2 * 4)
    private val audioLock = Any()
    private var audio: AudioTrack? = null

    // Written by openAudioTrack, read only by the thread that just called it.
    private var bufferBytes = 0
    private var perVideoFrameBytes = 0

    // ---- state -------------------------------------------------------------------
    @Volatile private var buttons = 0
    @Volatile private var running = false
    private var emulator: Emulator? = null
    private var thread: EmuThread? = null
    @Volatile private var surfaceReady = false
    @Volatile private var resumed = false

    /** Invoked on the emulator thread with a fresh copy of cartridge RAM when the game
     *  has written to it. The activity persists it; do not touch the UI from here. */
    var onSramDirty: ((ByteArray) -> Unit)? = null

    /** Once a second, on the emulator thread: (displayed fps, game fps). */
    var onFps: ((Float, Float) -> Unit)? = null

    /** True between a successful [loadRom] and [release]. */
    val isRomLoaded: Boolean get() = emulator != null

    init {
        holder.addCallback(this)
        setWillNotDraw(true)
    }

    // ---------------------------------------------------------------- public API --

    /**
     * Starts a new game, replacing any current one. [sram] is the battery save to restore
     * and is applied *before* the first frame runs, so the game never sees an empty
     * cartridge and then a full one. Must be called on the UI thread.
     *
     * @return false if the core would not accept the ROM.
     */
    fun loadRom(rom: ByteArray, sram: ByteArray? = null): Boolean {
        stopThread()
        emulator?.close()
        emulator = null

        val emu = Emulator.open(rom) ?: return false
        if (sram != null && !emu.writeSram(sram)) {
            // Wrong size for this cartridge, or no battery. Not fatal: play on without it
            // rather than refusing the ROM. The native side has already logged the sizes.
            Log.w(TAG, "battery save (${sram.size} bytes) did not fit this cartridge")
        }
        emulator = emu
        pixels.fill(Color.BLACK)
        keepScreenOn = true
        syncThread()
        return true
    }

    /** Cartridge RAM as it stands right now, or null if the cartridge has no battery. */
    fun readSram(): ByteArray? = emulator?.readSram()

    fun saveState(): ByteArray? = emulator?.saveState()

    fun reset() { emulator?.reset() }

    /** True when the game takes a Zapper (see [aim]). */
    val usesZapper: Boolean get() = emulator?.usesZapper() ?: false

    /** Point the Zapper at view position (x, y); [pressed] pulls the trigger. */
    fun aim(x: Float, y: Float, pressed: Boolean) {
        val d = destRect
        if (d.width() <= 0 || d.height() <= 0) return
        val fx = srcRect.left + kotlin.math.floor((x - d.left) * srcRect.width() / d.width()).toInt()
        val fy = srcRect.top + kotlin.math.floor((y - d.top) * srcRect.height() / d.height()).toInt()
        emulator?.setPointer(fx, fy, pressed)
    }
    fun mods(): List<Emulator.Mod> = emulator?.mods() ?: emptyList()
    fun modGet(i: Int): Int = emulator?.modGet(i) ?: 0
    fun modSet(i: Int, v: Int) { emulator?.modSet(i, v) }

    /** Player 2 buttons (Bluetooth pad), rewind and fast-forward; read by the loop. */
    @Volatile var buttons2 = 0

    /** Netplay running: the frame loop plays [NetPlay] steps instead of free frames.
     *  [netHost] = this phone runs the game (player 1); else it plays the host's records. */
    @Volatile var netPlay: NetPlay? = null
    @Volatile var netHost = true
    /** The session the lockstep runs over (player 1 reads player 2's pad lead from it). */
    var netSession: () -> NetSession? = { null }
    /** The loop waited on the other phone for this many frames in a row (0 = flowing). */
    @Volatile var netWaiting = 0

    /** The emulator as [NetPlay] sees it (called on the emulator thread). */
    fun netMachine(toggles: List<Int>): NetPlay.Machine = object : NetPlay.Machine {
        override fun run(pad1: Int, pad2: Int) {
            val e = emulator ?: return
            e.setButtons(pad1); e.setButtons2(pad2)
            // every frame's sound of this refresh is kept (two frames when catching up)
            val n = e.runFrame(pixels, netPcm)
            val room = pcm.size / 2 - netSamples
            val k = minOf(n, room).coerceAtLeast(0)
            System.arraycopy(netPcm, 0, pcm, netSamples * 2, k * 2)
            netSamples += k
        }
        override fun save(): ByteArray? = emulator?.saveState()
        override fun load(state: ByteArray): Boolean = emulator?.loadState(state) ?: false
        override fun hash(): Int = emulator?.ramHash() ?: 0
        override fun mods(): IntArray = IntArray(toggles.size) { modGet(toggles[it]) }
        override fun setMods(values: IntArray) { for (i in values.indices) if (i < toggles.size) modSet(toggles[i], values[i]) }
    }
    private var netSamples = 0
    private val netPcm = ShortArray(Emulator.SAMPLE_RATE / 50 * 2 * 4)
    /** Guest: records held back before playing, so a late packet does not stop the game (and its sound). */
    private var guestPrimed = false
    private var lastCatchUp = 0L

    /** Network game: frames that waited for the other phone / double steps to catch up (FPS line). */
    @Volatile var netStallCount = 0L
    @Volatile var netCatchUpCount = 0L
    @Volatile var rewind = false
    @Volatile var fastForward = false
    /** CRT look: [CrtShader] (scanlines, shadow mask, curved tube) or, before Android 13,
     *  a dark line under every game row. */
    @Volatile var crt = false
    /** The CRT filter's tube shape: warp and rounded corners (false = flat screen). */
    @Volatile var crtCurved = true
        set(v) { field = v; crtPlacedFor = Rect() }
    /** crt-lottes' bloom: bright parts glow (costs the GPU about twice as much). */
    @Volatile var crtBloom = false
        set(v) { field = v; crtPlacedFor = Rect() }
    /** Blargg's NTSC filter is on in the core: draw its 602 px wide frame. */
    @Volatile var ntsc = false
    /** The TV bezel (assets/bezels/tv.png, 21:9) around the picture when there is room. */
    @Volatile var bezel = false
    private val widePixels = IntArray(640 * Emulator.SCREEN_HEIGHT)
    private var wideBitmap: Bitmap? = null
    private val wideSrc = Rect()
    private val widePaint = Paint().apply { isFilterBitmap = true }   // 602 -> screen: smooth, not uneven columns
    private val bezelBitmap: Bitmap? by lazy {
        runCatching { context.assets.open("bezels/tv.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    private val bezelDst = android.graphics.RectF()
    private val bezelSrc = Rect()
    private val bezelFill = android.graphics.RectF()

    /**
     * Renders the frame a save state shows, without disturbing the running game: the
     * current state is kept aside, [state] loaded, one frame run (silently), and the
     * current state put back. Call while the loop is paused. Null if the state is unusable.
     */
    fun renderState(state: ByteArray): Bitmap? {
        val emu = emulator ?: return null
        val current = emu.saveState() ?: return null
        return try {
            if (!emu.loadState(state)) return null
            val px = IntArray(Emulator.SCREEN_PIXELS)
            emu.runFrame(px, null)
            Bitmap.createBitmap(px, 8, Emulator.SCREEN_WIDTH, Emulator.SCREEN_WIDTH - 16,
                Emulator.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
        } finally {
            emu.loadState(current)
        }
    }

    /** The last frame as shown (8 px cut each side). Call while the loop is paused. */
    fun snapshot(): Bitmap = Bitmap.createBitmap(
        pixels, 8, Emulator.SCREEN_WIDTH, Emulator.SCREEN_WIDTH - 16, Emulator.SCREEN_HEIGHT,
        Bitmap.Config.ARGB_8888)

    fun loadState(state: ByteArray): Boolean = emulator?.loadState(state) ?: false

    /** Replaces the whole held-button mask. Bits are Emulator.BTN_*. */
    fun setButtons(mask: Int) {
        buttons = mask
    }

    /** Presses or releases one or more buttons without disturbing the others. */
    @Synchronized
    fun setButton(mask: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or mask else buttons and mask.inv()
    }

    fun onActivityResume() {
        resumed = true
        syncThread()
    }

    fun onActivityPause() {
        resumed = false
        stopThread()
    }

    /** Paused (menu open): draw the last frame again, e.g. to show a new filter at once. */
    fun redraw() {
        if (thread == null && emulator != null) runCatching { renderFrame() }
    }

    /** Paused: the core makes the frame again (a new palette / NTSC signal) and the game
     *  is put back exactly where it was (one frame run silently, then the state restored). */
    fun repaint() {
        val emu = emulator ?: return
        if (thread != null) return
        runCatching {
            val st = emu.saveState() ?: return
            emu.runFrame(pixels, null)
            emu.loadState(st)
            renderFrame()
        }
    }

    /** Stops the loop and frees the core. The view is reusable afterwards via [loadRom]. */
    fun release() {
        stopThread()
        emulator?.close()
        emulator = null
        keepScreenOn = false
    }

    // ------------------------------------------------------------ surface callbacks --

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        syncThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Force the destination rect to be recomputed on the next frame.
        destForWidth = -1
        destForHeight = -1
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Must not return while the loop could still be inside lockCanvas, so the thread
        // is stopped and joined here rather than merely flagged.
        surfaceReady = false
        stopThread()
    }

    /**
     * Sizes the view to the largest whole multiple of 160x144 that fits, so the surface
     * itself is pixel-exact and the parent's black background provides the letterbox.
     * An EXACTLY spec (a parent that insists on match_parent) is always obeyed; the blit
     * then letterboxes inside the surface instead. Either way the picture is drawn at an
     * integer scale -- a fractional one makes a Game Boy screen shimmer as it scrolls.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        if (wMode == MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val availW = if (wMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthMeasureSpec)
        val availH = if (hMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(heightMeasureSpec)
        val scale = integerScale(availW, availH)
        setMeasuredDimension(Emulator.SCREEN_WIDTH * scale, Emulator.SCREEN_HEIGHT * scale)
    }

    // ------------------------------------------------------------- thread plumbing --

    /** Starts the loop when, and only when, all three preconditions hold. */
    private fun syncThread() {
        if (emulator != null && resumed && surfaceReady && thread == null) {
            running = true
            thread = EmuThread().also { it.start() }
        }
    }

    private fun stopThread() {
        val t = thread ?: return
        thread = null
        running = false
        // A blocking write() can be parked for a whole audio buffer. pause() releases it
        // immediately, which is the difference between joining in 2 ms and in 60 ms.
        synchronized(audioLock) {
            runCatching {
                audio?.pause()
                audio?.flush()
            }
        }
        t.quitSafely()
        t.join(2000)
        if (t.isAlive) {
            Log.w(TAG, "emulator thread did not stop in 2 s; interrupting")
            t.interrupt()
            t.join(1000)
        }
    }

    private fun integerScale(availW: Int, availH: Int): Int =
        max(1, min(availW / Emulator.SCREEN_WIDTH, availH / Emulator.SCREEN_HEIGHT))

    private fun computeDest(surfaceW: Int, surfaceH: Int) {
        // Near-square screen (unfolded Fold): stretch the frame over the whole surface.
        // The aspect error is a few percent and at ~9x the uneven pixel widths are
        // 1 screen px in 9.
        val cropW = Emulator.SCREEN_WIDTH - 2 * FILL_CROP_X
        val stretch = (surfaceW.toFloat() / surfaceH) / (cropW.toFloat() / Emulator.SCREEN_HEIGHT)
        if (stretch in 0.92f..1.08f) {
            srcRect.set(FILL_CROP_X, 0, Emulator.SCREEN_WIDTH - FILL_CROP_X, Emulator.SCREEN_HEIGHT)
            destRect.set(0, 0, surfaceW, surfaceH)
            letterboxed = false
            destForWidth = surfaceW
            destForHeight = surfaceH
            return
        }
        // Wide screen (folded, cover display): full height, NES TV aspect (pixels 8:7
        // wider than tall, as a CRT showed them). The side bands hold the controls.
        if (stretch > 1.08f) {
            val w = (surfaceH * cropW * 8f / 7f / Emulator.SCREEN_HEIGHT).toInt()
            if (w <= surfaceW) {
                srcRect.set(FILL_CROP_X, 0, Emulator.SCREEN_WIDTH - FILL_CROP_X, Emulator.SCREEN_HEIGHT)
                val left = (surfaceW - w) / 2
                destRect.set(left, 0, left + w, surfaceH)
                letterboxed = true
                destForWidth = surfaceW
                destForHeight = surfaceH
                return
            }
        }
        srcRect.set(0, 0, Emulator.SCREEN_WIDTH, Emulator.SCREEN_HEIGHT)
        val scale = integerScale(surfaceW, surfaceH)
        val w = Emulator.SCREEN_WIDTH * scale
        val h = Emulator.SCREEN_HEIGHT * scale
        val left = (surfaceW - w) / 2
        val top = (surfaceH - h) / 2
        destRect.set(left, top, left + w, top + h)
        letterboxed = left != 0 || top != 0
        destForWidth = surfaceW
        destForHeight = surfaceH
    }

    /** Called from the emulator thread, or from [redraw] while that thread is stopped. */
    private fun renderFrame() {
        if (!surfaceReady) return
        bitmap.setPixels(
            pixels, 0, Emulator.SCREEN_WIDTH,
            0, 0, Emulator.SCREEN_WIDTH, Emulator.SCREEN_HEIGHT
        )
        val h = holder
        val canvas = try {
            h.lockHardwareCanvas()
        } catch (e: IllegalStateException) {
            // The surface went away between the check and here.
            null
        } ?: return
        try {
            if (canvas.width != destForWidth || canvas.height != destForHeight) {
                computeDest(canvas.width, canvas.height)
            }
            // Only when the surface is bigger than the picture; in the normal case
            // onMeasure has made them the same size and this whole fill is skipped.
            if (letterboxed) canvas.drawColor(Color.BLACK)
            // NTSC filter: its wide frame; the same part of the picture, in its pixels.
            val ww = if (ntsc) emulator?.wideFrame(widePixels) ?: 0 else 0
            var src = bitmap; var srcR = srcRect
            if (ww > 0) {
                val wb = wideBitmap?.takeIf { it.width == ww }
                    ?: Bitmap.createBitmap(ww, Emulator.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }.also { wideBitmap = it; crtPlacedFor = Rect() }
                wb.setPixels(widePixels, 0, ww, 0, 0, ww, Emulator.SCREEN_HEIGHT)
                wideSrc.set(srcRect.left * ww / Emulator.SCREEN_WIDTH, srcRect.top, srcRect.right * ww / Emulator.SCREEN_WIDTH, srcRect.bottom)
                src = wb; srcR = wideSrc
            }
            val gpuCrt = if (crt) crtShader else null
            if (gpuCrt != null) {
                if (crtPlacedFor != destRect || crtBoundTo !== src) {
                    gpuCrt.bind(src); crtBoundTo = src
                    gpuCrt.place(srcR, destRect, crtCurved, crtBloom); crtPlacedFor = Rect(destRect)
                }
                canvas.drawRect(destRect, gpuCrt.paint)
            } else canvas.drawBitmap(src, srcR, destRect, if (ww > 0) widePaint else paint)
            // The TV around the picture (wide screens): its opening fitted to the picture's height.
            val bz = if (bezel && letterboxed) bezelBitmap else null
            if (bz != null) {
                val k = bz.height / BEZEL_IMG_H                       // decoded size vs the file's
                val s = destRect.height() / (BEZEL_WIN_H * k)          // its opening = the picture's height
                val bw = bz.width * s; val bh = bz.height * s
                val left = destRect.exactCenterX() - BEZEL_WIN_CX * k * s
                val top = destRect.exactCenterY() - BEZEL_WIN_CY * k * s
                bezelDst.set(left, top, left + bw, top + bh)
                canvas.drawBitmap(bz, null, bezelDst, widePaint)
                // The picture is 16:9, the folded screen wider: its plain outermost strip of
                // cabinet (8 px, no logo there) is drawn on out to the screen's edges.
                if (bezelDst.left > 0f) {
                    bezelSrc.set(0, 0, (8 * k).toInt(), bz.height)
                    bezelFill.set(0f, top, bezelDst.left + 1f, top + bh)
                    canvas.drawBitmap(bz, bezelSrc, bezelFill, widePaint)
                }
                if (bezelDst.right < canvas.width) {
                    bezelSrc.set(bz.width - (8 * k).toInt(), 0, bz.width, bz.height)
                    bezelFill.set(bezelDst.right - 1f, top, canvas.width.toFloat(), top + bh)
                    canvas.drawBitmap(bz, bezelSrc, bezelFill, widePaint)
                }
            }
            if (crt && gpuCrt == null) {
                val rowH = destRect.height() / Emulator.SCREEN_HEIGHT.toFloat()
                val lineH = rowH * 0.38f
                var y = destRect.top + rowH - lineH
                while (y < destRect.bottom) {
                    canvas.drawRect(destRect.left.toFloat(), y, destRect.right.toFloat(), y + lineH, crtPaint)
                    y += rowH
                }
            }
        } finally {
            runCatching { h.unlockCanvasAndPost(canvas) }
        }
    }

    private fun openAudioTrack(sampleRate: Int): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBuf; running without sound")
            return null
        }
        // 2 bytes * 2 channels * ~738 stereo frames = about 2953 bytes of audio per video
        // frame. The buffer is the reservoir the loop paces against, so it is also the
        // audio latency: three frames (about 50 ms) is the smallest that still survives a
        // slow frame, and the device minimum wins whenever it is larger.
        perVideoFrameBytes = (sampleRate * 4) / 59 + 4
        val bufBytes = max(minBuf, perVideoFrameBytes * 8)
        bufferBytes = bufBytes
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack could not be built; running without sound", e)
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack state ${track.state}; running without sound")
            track.release()
            return null
        }
        Log.i(TAG, "audio: ${sampleRate}Hz stereo, buffer $bufBytes bytes (min $minBuf)")
        return track
    }

    /**
     * The frame loop, driven by the display's vsync (Choreographer) instead of the audio
     * buffer. Audio-paced loops run frames in bursts (the mixer drains its buffer a period
     * at a time) and post them at random points between refreshes, which reads as judder
     * on a 120 Hz panel even at a steady 60 fps. Here exactly one game frame is run on
     * every 1/60 s worth of vsyncs, and the audio clock is bent to match instead: the
     * AudioTrack playback rate is trimmed by a small feedback loop on its buffer fill.
     */
    private inner class EmuThread : HandlerThread("gbc-emu", Process.THREAD_PRIORITY_URGENT_DISPLAY),
        Choreographer.FrameCallback {

        private lateinit var emu: Emulator
        private var track: AudioTrack? = null
        private var audioAlive = false
        private var written = 0L          // stereo frames handed to the track
        private var baseRate = 0
        private var nextDue = 0L
        private var fpsAt = 0L
        private var fpsFrames = 0L
        private var fpsGame = 0L
        private var frames = 0L
        private var sramPending = false
        private var sramSavedAt = 0L

        override fun onLooperPrepared() {
            emu = emulator ?: return
            requestSixtyHz()
            val t = openAudioTrack(emu.sampleRate)
            synchronized(audioLock) { audio = t }
            track = t
            if (t != null) {
                // Game runs at exactly 60.000 fps (vsync), not the NES's 60.0988, so it
                // produces 0.16% fewer samples per second; play them that much slower.
                baseRate = (emu.sampleRate * 60.0 / 60.0988).toInt()
                t.playbackRate = baseRate
                val prime = ShortArray(targetFill() * 2)
                t.write(prime, 0, prime.size, AudioTrack.WRITE_NON_BLOCKING)
                written = prime.size / 2L
                t.play()
                audioAlive = true
            }
            fpsAt = System.nanoTime()
            fpsGame = emu.gameFrames()
            Choreographer.getInstance().postFrameCallback(this)
        }

        /** Audio reservoir target: four video frames (~67 ms). */
        /** Four frames of sound in reserve (~67 ms); eight in a network game, whose frames come less evenly. */
        private fun targetFill() = emu.sampleRate / 60 * (if (netPlay != null) 8 else 4)

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            // On a 120 Hz panel every second vsync is due; half a refresh of slack keeps
            // the choice stable against timestamp jitter. Fall back on a long stall.
            if (nextDue == 0L || frameTimeNanos - nextDue > RESYNC_NANOS) nextDue = frameTimeNanos
            if (frameTimeNanos < nextDue - SLACK_NANOS) return
            val period = if (netPlay != null && netHost) hostPeriod else PERIOD_NANOS
            nextDue += period
            try {
                // Waiting for the other phone (a rare hiccup now): try again on the very next
                // refresh, not a whole frame later.
                if (!step()) nextDue -= period
            } catch (t: Throwable) {
                Log.e(TAG, "frame failed", t)
            }
        }

        /** Netplay: one lockstep step (two when the guest is behind); turbo is resolved here
         *  by the step number, so both phones pulse it on the same frames. */
        private fun netStep(np: NetPlay): Int {
            emu.setRewind(false)
            netSamples = 0
            var played = 0
            if (netHost) {
                if (np.hostStep(resolveTurbo(buttons, np.step))) { played = 1; paceToGuest(np) }
            } else {
                // A small jitter buffer: start (and restart after running dry) only with
                // JITTER records in hand; catch up with two a refresh when far behind.
                np.guestBegin()
                val bl = np.backlog()
                if (!guestPrimed && bl >= JITTER) guestPrimed = true
                if (guestPrimed) {
                    // No catching up in normal play: a double step is a visible jump of the picture.
                    // The phones' panels differ slightly (the A22's "60 Hz" really is 59.52, the
                    // Fold's 120 Hz exact), so this phone may consume a little slower; then player
                    // 1's pads come late now and then and that phone waits one of its refreshes
                    // (8 ms at 120 Hz) - the faster phone follows the slower one smoothly instead.
                    // Only far behind (a hiccup, a pause) catch up, at once.
                    val behind = bl > JITTER + 10
                    val runs = if (behind) 2 else 1
                    if (behind) { lastCatchUp = frames; netCatchUpCount++ }
                    for (i in 0 until runs) if (np.guestStep(resolveTurbo(buttons, np.step + np.delay))) played++ else { guestPrimed = false; break }
                }
            }
            netWaiting = if (played == 0) netWaiting + 1 else 0
            if (played == 0 && (netHost || guestPrimed || np.step > 0)) netStallCount++
            return if (played == 0) -1 else netSamples
        }

        // ---- player 1 keeps its own even rhythm, a hair slower when player 2's phone is slower.
        // Panels differ (the A22's "60 Hz" is 59.52); waiting for each late pad made player 1's
        // frames come 8 / 25 ms apart (log 00:37: 61 waits a second). Instead a slow PI loop holds
        // player 2's pads TARGET_LEAD steps ahead by lengthening the frame period a little.
        private var hostPeriod = PERIOD_NANOS
        private var leadAvg = TARGET_LEAD.toDouble()
        private var paceI = 0.0

        private fun paceToGuest(np: NetPlay) {
            val lead = (netSession()?.guestPadMax() ?: return) - np.step
            leadAvg += (lead - leadAvg) * 0.02
            val err = TARGET_LEAD - leadAvg                         // > 0: pads come too late
            // never faster than 60 (with two equal phones that only made 8 / 25 ms pairs)
            paceI = (paceI + err * 0.00002).coerceIn(0.0, 0.03)
            val adj = (err * 0.003 + paceI).coerceIn(0.0, 0.04)     // up to 4% slower
            hostPeriod = (PERIOD_NANOS * (1.0 + adj)).toLong()
        }

        /** False = a network game waited for the other phone (nothing ran). */
        private fun step(): Boolean {
            val np = netPlay
            val n: Int
            if (np != null) {
                n = netStep(np)
                if (n < 0) return false    // waiting for the other phone: the picture stays
            } else {
            emu.setButtons(buttons)
            emu.setButtons2(buttons2)
            val rw = rewind
            emu.setRewind(rw)
            // Fast-forward: two extra frames per refresh (3x), their sound dropped.
            if (fastForward && !rw) { emu.runFrame(null, null); emu.runFrame(null, null) }
            n = emu.runFrame(pixels, pcm)
            }
            val t = track
            if (audioAlive && t != null && n > 0) {
                val wrote = t.write(pcm, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                if (wrote < 0) { Log.e(TAG, "AudioTrack.write returned $wrote"); audioAlive = false }
                else written += wrote / 2
                // Feedback: keep the buffer near target by nudging the playback rate
                // (max +-0.5%, inaudible) instead of ever blocking the video loop.
                val fill = written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                val err = (fill - targetFill()).toDouble() / targetFill()
                t.playbackRate = (baseRate * (1.0 + (err * 0.005).coerceIn(-0.005, 0.005))).toInt()
            }
            renderFrame()
            frames++

            fpsFrames++
            val now = System.nanoTime()
            if (now - fpsAt >= 1_000_000_000L) {
                val dt = (now - fpsAt) / 1e9f
                val game = emu.gameFrames()
                onFps?.invoke(fpsFrames / dt, (game - fpsGame) / dt)
                fpsAt = now; fpsFrames = 0L; fpsGame = game
            }

            if (frames % 60L == 0L) {
                if (emu.isSramDirty()) sramPending = true
                if (sramPending && now - sramSavedAt > SRAM_MIN_INTERVAL_NANOS) {
                    emu.readSram()?.let { onSramDirty?.invoke(it) }
                    sramPending = false
                    sramSavedAt = now
                }
            }
            return true
        }

        /** Called on this thread after the looper quits. */
        fun shutdownAudio() {
            synchronized(audioLock) {
                audio = null
                track?.let {
                    runCatching { if (it.state == AudioTrack.STATE_INITIALIZED) it.stop() }
                    it.release()
                }
                track = null
            }
        }

        override fun run() {
            try {
                super.run()
            } finally {
                shutdownAudio()
            }
        }
    }

    /** Ask the compositor for a 60-multiple refresh so frames land on every 1st/2nd vsync. */
    private fun requestSixtyHz() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                // CHANGE_FRAME_RATE_ALWAYS: a panel that cannot go 90 -> 60 Hz seamlessly (Galaxy
                // A22) otherwise stays at 90 and only pretends 60 to the app (judder).
                if (Build.VERSION.SDK_INT >= 31)
                    holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                else holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    private companion object {
        // the bezel's opening in its 2552x1096 picture (alpha < 50%): x 570..1980, y 8..1085
        const val BEZEL_IMG_H = 1096f
        const val BEZEL_WIN_H = 1078f
        const val BEZEL_WIN_CX = 1275f
        const val BEZEL_WIN_CY = 546.5f
        /** Turbo bits -> plain A/B pulsed by [step] (2 on, 2 off), the same on both phones. */
        fun resolveTurbo(mask: Int, step: Int): Int {
            var m = mask and (GbButtons.TURBO_A or GbButtons.TURBO_B).inv()
            if (step and 2 == 0) {
                if (mask and GbButtons.TURBO_A != 0) m = m or GbButtons.A
                if (mask and GbButtons.TURBO_B != 0) m = m or GbButtons.B
            }
            return m
        }
        const val TAG = "gbcplayer"
        const val JITTER = 2
        const val RESYNC_NANOS = 250_000_000L
        const val PERIOD_NANOS = 16_666_667L
        const val TARGET_LEAD = 4
        const val FILL_CROP_X = 8
        const val SLACK_NANOS = 4_000_000L
        const val SRAM_MIN_INTERVAL_NANOS = 3_000_000_000L
    }
}
