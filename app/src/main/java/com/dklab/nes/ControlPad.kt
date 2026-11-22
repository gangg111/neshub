package com.dklab.nes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Packed Game Boy button bits, in the order binjgb's `joypad_pack_buttons` uses.
 * The whole state of the pad is one byte, which is exactly what the core wants.
 */
object GbButtons {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    /** Turbo fire: the core pulses A/B itself (2 frames on, 2 off = 15 Hz). */
    const val TURBO_A = 1 shl 8
    const val TURBO_B = 1 shl 9
    /** Not an emulator button: opens the in-game menu (handled by the activity). */
    const val MENU = 1 shl 10
    /** Held: rewind / fast-forward (handled by the activity, never sent to the game). */
    const val REWIND = 1 shl 11
    const val FFWD = 1 shl 12

    /** Debug helper: "UP|LEFT|B" for a mask. */
    fun describe(mask: Int): String {
        if (mask == 0) return "-"
        val names = arrayOf("A", "B", "SELECT", "START", "RIGHT", "LEFT", "UP", "DOWN", "TA", "TB", "MENU", "REW", "FF")
        return (0..12).filter { mask and (1 shl it) != 0 }.joinToString("|") { names[it] }
    }
}

/**
 * Where every control sits, in view pixels. Produced by [PadGeometry.layout] and
 * consumed by both the hit testing and the drawing, so the art and the touch
 * targets can never drift apart.
 *
 * Hit radii are deliberately larger than the drawn radii: the art stays small so
 * it does not swallow the screen, while the finger gets a target it can actually
 * find without looking.
 */
data class PadLayout(
    @JvmField val dpadCx: Float,
    @JvmField val dpadCy: Float,
    /** Arm length of the drawn cross. */
    @JvmField val dpadRadius: Float,
    @JvmField val dpadHitRadius: Float,
    /** Touches nearer the centre than this press nothing, so a resting thumb does not drift. */
    @JvmField val dpadDeadZone: Float,
    @JvmField val aCx: Float,
    @JvmField val aCy: Float,
    @JvmField val bCx: Float,
    @JvmField val bCy: Float,
    @JvmField val faceRadius: Float,
    @JvmField val faceHitRadius: Float,
    @JvmField val selectCx: Float,
    @JvmField val startCx: Float,
    @JvmField val pillCy: Float,
    @JvmField val pillHalfW: Float,
    @JvmField val pillHalfH: Float,
    @JvmField val pillHitPad: Float,
    /** Save/load pills, top-right corner. */
    /** Top row (◄◄ ►► MENU): bigger pills with the label inside. */
    @JvmField val topHalfW: Float = 0f,
    @JvmField val topHalfH: Float = 0f,
    @JvmField val menuCx: Float = -1e6f,
    @JvmField val ffCx: Float = -1e6f,
    @JvmField val rewCx: Float = -1e6f,
    @JvmField val topPillCy: Float = -1e6f,
    /** Per top button: own centre Y and size factor (the layout editor moves/scales them). */
    @JvmField val menuCy: Float = topPillCy,
    @JvmField val ffCy: Float = topPillCy,
    @JvmField val rewCy: Float = topPillCy,
    @JvmField val menuS: Float = 1f,
    @JvmField val ffS: Float = 1f,
    @JvmField val rewS: Float = 1f,
)

/** Pure layout-editor geometry (no View): moving/scaling groups and their bounds. */
/** Plain rectangle (android.graphics.RectF is a stub off-device, this runs in tests). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

object PadEdit {
    /** Base layout -> moved/scaled groups, each kept fully on screen. */
    fun apply(b: PadLayout, tfs: Map<PadGroup, GroupTf>, d: Float, width: Int, height: Int): PadLayout {
        var l = b
        // D-pad
        tfs.getValue(PadGroup.DPAD).let { t ->
            l = l.copy(dpadCx = b.dpadCx + t.dx * d, dpadCy = b.dpadCy + t.dy * d,
                dpadRadius = b.dpadRadius * t.scale, dpadHitRadius = b.dpadHitRadius * t.scale,
                dpadDeadZone = b.dpadDeadZone * t.scale)
        }
        tfs.getValue(PadGroup.AB).let { t ->
            val gx = (b.aCx + b.bCx) / 2
            l = l.copy(aCx = gx + t.dx * d + (b.aCx - gx) * t.scale, bCx = gx + t.dx * d + (b.bCx - gx) * t.scale,
                aCy = b.aCy + t.dy * d, bCy = b.bCy + t.dy * d,
                faceRadius = b.faceRadius * t.scale, faceHitRadius = b.faceHitRadius * t.scale)
        }
        tfs.getValue(PadGroup.SELSTART).let { t ->
            val gx = (b.selectCx + b.startCx) / 2
            l = l.copy(selectCx = gx + t.dx * d + (b.selectCx - gx) * t.scale,
                startCx = gx + t.dx * d + (b.startCx - gx) * t.scale, pillCy = b.pillCy + t.dy * d,
                pillHalfW = b.pillHalfW * t.scale, pillHalfH = b.pillHalfH * t.scale)
        }
        tfs.getValue(PadGroup.REW).let { t -> l = l.copy(rewCx = b.rewCx + t.dx * d, rewCy = b.topPillCy + t.dy * d, rewS = t.scale) }
        tfs.getValue(PadGroup.FF).let { t -> l = l.copy(ffCx = b.ffCx + t.dx * d, ffCy = b.topPillCy + t.dy * d, ffS = t.scale) }
        tfs.getValue(PadGroup.MENU).let { t -> l = l.copy(menuCx = b.menuCx + t.dx * d, menuCy = b.topPillCy + t.dy * d, menuS = t.scale) }
        // keep every group on screen
        for (g in PadGroup.values()) {
            val r = bounds(l, g)
            val sx = when { r.left < 0 -> -r.left; r.right > width -> width - r.right; else -> 0f }
            val sy = when { r.top < 0 -> -r.top; r.bottom > height -> height - r.bottom; else -> 0f }
            if (sx != 0f || sy != 0f) l = shift(l, g, sx, sy)
        }
        return l
    }

    private fun shift(l: PadLayout, g: PadGroup, sx: Float, sy: Float): PadLayout = when (g) {
        PadGroup.DPAD -> l.copy(dpadCx = l.dpadCx + sx, dpadCy = l.dpadCy + sy)
        PadGroup.AB -> l.copy(aCx = l.aCx + sx, bCx = l.bCx + sx, aCy = l.aCy + sy, bCy = l.bCy + sy)
        PadGroup.SELSTART -> l.copy(selectCx = l.selectCx + sx, startCx = l.startCx + sx, pillCy = l.pillCy + sy)
        PadGroup.REW -> l.copy(rewCx = l.rewCx + sx, rewCy = l.rewCy + sy)
        PadGroup.FF -> l.copy(ffCx = l.ffCx + sx, ffCy = l.ffCy + sy)
        PadGroup.MENU -> l.copy(menuCx = l.menuCx + sx, menuCy = l.menuCy + sy)
    }

    /** Everything a group draws (housings, labels), for picking, outlines and clamping. */
    fun bounds(l: PadLayout, g: PadGroup): Box = when (g) {
        PadGroup.DPAD -> Box(l.dpadCx - l.dpadRadius, l.dpadCy - l.dpadRadius, l.dpadCx + l.dpadRadius, l.dpadCy + l.dpadRadius)
        PadGroup.AB -> {
            val r = l.faceRadius
            Box(min(l.aCx, l.bCx) - r * 1.52f, l.aCy - r * 1.52f, max(l.aCx, l.bCx) + r * 1.52f, l.aCy + r * 1.97f)
        }
        PadGroup.SELSTART -> {
            val pad = l.pillHalfH * 0.55f
            Box(min(l.selectCx, l.startCx) - l.pillHalfW - pad, l.pillCy - l.pillHalfH - pad - l.pillHalfH * 1.6f,
                max(l.selectCx, l.startCx) + l.pillHalfW + pad, l.pillCy + l.pillHalfH + pad)
        }
        PadGroup.REW -> topBounds(l, l.rewCx, l.rewCy, l.rewS)
        PadGroup.FF -> topBounds(l, l.ffCx, l.ffCy, l.ffS)
        PadGroup.MENU -> topBounds(l, l.menuCx, l.menuCy, l.menuS)
    }

    private fun topBounds(l: PadLayout, cx: Float, cy: Float, s: Float): Box {
        val pad = l.topHalfH * s * 0.3f
        return Box(cx - l.topHalfW * s - pad, cy - l.topHalfH * s - pad, cx + l.topHalfW * s + pad, cy + l.topHalfH * s + pad)
    }

}

/** Groups the layout editor moves and resizes as one piece. */
enum class PadGroup { DPAD, AB, SELSTART, REW, FF, MENU }

/** One group's edit: offset in dp, size factor. */
class GroupTf(var dx: Float = 0f, var dy: Float = 0f, var scale: Float = 1f)

/**
 * Pure geometry: no Android types, no view state, no side effects. Kept separate
 * from [ControlPad] precisely so it can be compiled and exercised on a desktop JVM
 * (see the harness in the task write-up) instead of only being testable on-device.
 */
object PadGeometry {

    /**
     * Half-width of a cardinal sector, in degrees. The eight sectors tile the circle
     * exactly: cardinals are 2*20 = 40 degrees wide, the four diagonals get the rest,
     * 2*(45-20) = 50 degrees each. Diagonals are the *wider* targets on purpose --
     * "up-left" is what a shmup player is aiming for and what four separate rectangles
     * would make impossible to hit.
     */
    const val CARDINAL_HALF_DEG = 20f

    /** Sector k is centred on 45*k degrees, measured counter-clockwise from +x (right). */
    private val SECTOR_MASK = intArrayOf(
        GbButtons.RIGHT,                    // 0   E
        GbButtons.RIGHT or GbButtons.UP,    // 45  NE
        GbButtons.UP,                       // 90  N
        GbButtons.UP or GbButtons.LEFT,     // 135 NW
        GbButtons.LEFT,                     // 180 W
        GbButtons.LEFT or GbButtons.DOWN,   // 225 SW
        GbButtons.DOWN,                     // 270 S
        GbButtons.DOWN or GbButtons.RIGHT,  // 315 SE
    )

    /**
     * The D-pad as one circular region: the direction comes from the *angle* of the
     * touch relative to the centre, never from which of four boxes was hit.
     *
     * @param dx touch x minus D-pad centre x
     * @param dy touch y minus D-pad centre y, screen coordinates (y grows downwards)
     */
    fun dpadMask(dx: Float, dy: Float, hitRadius: Float, deadZone: Float): Int {
        val r = hypot(dx, dy)
        if (r > hitRadius || r < deadZone) return 0
        // Negate dy so the angle is the usual maths one (counter-clockwise, up = +90).
        var deg = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        for (k in 0..7) {
            val half = if (k % 2 == 0) CARDINAL_HALF_DEG else 45f - CARDINAL_HALF_DEG
            if (angleDistance(deg, 45f * k) <= half) return SECTOR_MASK[k]
        }
        return 0 // unreachable: the eight sectors cover all 360 degrees
    }

    /** Smallest absolute difference between two angles in degrees, 0..180. */
    private fun angleDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * Buttons pressed by a single pointer at (x, y). Regions are tested in priority
     * order and a pointer only ever presses one region, so a finger cannot smear the
     * D-pad and a face button together; two fingers do that, which is the point of
     * the multitouch handling in [ControlPad.onTouchEvent].
     */
    fun maskAt(l: PadLayout, x: Float, y: Float): Int {
        val dpad = dpadMask(x - l.dpadCx, y - l.dpadCy, l.dpadHitRadius, l.dpadDeadZone)
        if (dpad != 0) return dpad

        val da = hypot(x - l.aCx, y - l.aCy)
        val db = hypot(x - l.bCx, y - l.bCy)
        val aHit = da <= l.faceHitRadius
        val bHit = db <= l.faceHitRadius
        if (aHit && bHit) return if (da <= db) GbButtons.A else GbButtons.B
        if (aHit) return GbButtons.A
        if (bHit) return GbButtons.B

        fun top(cx: Float, cy: Float, s: Float) =
            abs(y - cy) <= l.topHalfH * s + l.pillHitPad && abs(x - cx) <= l.topHalfW * s + l.pillHitPad
        if (top(l.menuCx, l.menuCy, l.menuS)) return GbButtons.MENU
        if (top(l.ffCx, l.ffCy, l.ffS)) return GbButtons.FFWD
        if (top(l.rewCx, l.rewCy, l.rewS)) return GbButtons.REWIND

        if (abs(y - l.pillCy) <= l.pillHalfH + l.pillHitPad) {
            val padX = l.pillHalfW + l.pillHitPad
            if (abs(x - l.selectCx) <= padX) return GbButtons.SELECT
            if (abs(x - l.startCx) <= padX) return GbButtons.START
        }
        return 0
    }

    /**
     * Lay the pad out for a view of [width] x [height] pixels. Sized in dp and then
     * clamped to the space available, so it looks the same on any density and still
     * degrades sanely into a short control strip.
     *
     * Landscape hardware layout: D-pad bottom left, A and B bottom right on the same
     * diagonal tilt the real console uses (A up and to the right of B), START and
     * SELECT small and centred in the gap between them.
     */
    fun layout(width: Int, height: Int, density: Float): PadLayout {
        val w = width.toFloat()
        val h = height.toFloat()
        fun dp(v: Float) = v * density

        val margin = dp(14f)
        // One base measurement, two consumers. The cross is drawn 30% over it
        // because the artwork's arms stop short of their box and it reads smaller
        // than it measures; A and B keep the ORIGINAL base, so growing the cross
        // does not drag the face buttons up with it.
        val padBase = max(dp(18f), min(dp(58f), min(h * 0.30f, w * 0.16f)))
        val dpadRadius = padBase * 1.30f
        val dpadHit = dpadRadius * 1.45f
        val faceRadius = padBase * 0.46f
        val faceHit = faceRadius * 1.8f

        val dpadCx = margin + dpadRadius * 1.15f
        val dpadCy = h - margin - dpadRadius * 1.15f

        // A/B pair side by side, B left of A, as on the NES pad.
        val tilt = 0.0 // NES pad: B and A side by side on one level
        val spread = faceRadius * 1.35f
        // far enough in that A's housing and its plate stay on screen
        val pairCx = w - margin - faceRadius * 2.9f
        val pairCy = h - margin - faceRadius * 2.1f
        val aCx = pairCx + (spread * cos(tilt)).toFloat()
        val aCy = pairCy - (spread * sin(tilt)).toFloat()
        val bCx = pairCx - (spread * cos(tilt)).toFloat()
        val bCy = pairCy + (spread * sin(tilt)).toFloat()

        // START/SELECT go in the free band between the two thumb clusters, so they can
        // never end up underneath the D-pad's (deliberately oversized) hit circle.
        val bandL = dpadCx + dpadHit + dp(4f)
        val bandR = min(aCx, bCx) - faceHit - dp(4f)
        val bandC = (bandL + bandR) * 0.5f
        val pillHalfW = max(dp(14f), min(dp(30f), (bandR - bandL) * 0.21f))
        val pillHalfH = dp(9f)
        // Wide screens (folded / cover display): SELECT and START go to the top-left corner,
        // mirroring the rewind/ff/MENU row on the right; the bottom stays for the thumbs.
        // The group box (see PadEdit.bounds) is label + pill: 3.15 x pillHalfH above centre.
        val wide = w / h > 1.5f
        val pillGap = pillHalfW * 1.3f
        val selCx = if (wide) margin + pillHalfW + pillHalfH * 0.55f else bandC - pillGap
        val pillCy = if (wide) margin + pillHalfH * 3.15f else h - margin - pillHalfH

        return PadLayout(
            dpadCx = dpadCx,
            dpadCy = dpadCy,
            dpadRadius = dpadRadius,
            dpadHitRadius = dpadHit,
            dpadDeadZone = dpadRadius * 0.18f,
            aCx = aCx, aCy = aCy,
            bCx = bCx, bCy = bCy,
            faceRadius = faceRadius,
            faceHitRadius = faceHit,
            selectCx = selCx,
            startCx = selCx + pillGap * 2f,
            pillCy = pillCy,
            pillHalfW = pillHalfW,
            pillHalfH = pillHalfH,
            pillHitPad = dp(11f),
            // Turbo pair sits straight above A/B, same tilt, one button-row higher.
            topHalfW = dp(34f),
            topHalfH = dp(17f),
            menuCx = w - margin - dp(34f) - dp(4f),
            ffCx = w - margin - dp(34f) * 3.7f - dp(4f),
            rewCx = w - margin - dp(34f) * 6.4f - dp(4f),
            topPillCy = margin + dp(17f) + dp(4f),
        )
    }
}

/**
 * On-screen Game Boy controls: a D-pad, A and B, START and SELECT, drawn entirely
 * with Canvas primitives (no bitmaps, so it is sharp at any size) and driven by a
 * proper multitouch handler.
 *
 * The pressed state is exposed as one packed byte, bit order A=0, B=1, SELECT=2,
 * START=3, RIGHT=4, LEFT=5, UP=6, DOWN=7 -- the order binjgb's joypad_pack_buttons
 * expects, so the value can go straight into the core.
 *
 * Wiring from the activity, either style:
 *
 *     pad.onButtonsChanged = { mask -> emulator.setButtons(mask) }   // push
 *     val mask = pad.buttons                                         // pull, from the emu thread
 *
 * Physical gamepads and keyboards: the view handles keys itself when focused, and
 * [handleKeyEvent] / [handleMotionEvent] are public so an activity that keeps focus
 * elsewhere can forward its own onKeyDown/onKeyUp/onGenericMotionEvent instead.
 */
class ControlPad @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Called on the UI thread whenever the packed state changes. */
    var onButtonsChanged: ((Int) -> Unit)? = null

    /**
     * Light-gun games: the cross and A/B are hidden and every touch that is not on a
     * remaining button (SELECT, START, the top row) aims there instead -- [onAim] gets
     * view coordinates and whether the finger is down (the trigger).
     */
    var aimMode: Boolean = false
        set(value) { field = value; invalidate() }
    var onAim: ((x: Float, y: Float, down: Boolean) -> Unit)? = null
    private var aimPointer = -1

    /** Overlay opacity 0..255 and control size factor (menu → Nakładka). */
    var overlayAlpha: Int = OVERLAY_ALPHA
        set(v) { field = v; invalidate() }
    /** Control size factor per layout class ("wide" folded, "square" unfolded). */
    var classSizes: Map<String, Float> = mapOf("wide" to 1f, "square" to 1f)
        set(v) { field = v; relayout(); invalidate() }
    /** Size factor of the layout class in use. */
    val sizeScale: Float get() = classSizes[layoutClass] ?: 1f
    fun setClassSize(v: Float) { classSizes = classSizes + (layoutClass to v) }

    /** A short tick when a new button goes down. */
    var hapticsEnabled: Boolean = true

    /**
     * Current packed button state. Volatile because the emulator runs on its own
     * thread and polls this while the UI thread writes it.
     */
    @Volatile
    var buttons: Int = 0
        private set

    // The three input sources are kept apart and OR'd together, so a Bluetooth pad
    // and a thumb on the glass can be used at the same time and neither clears the
    // other's bits.
    private var touchMask = 0
    private var keyMask = 0
    private var axisMask = 0

    private var pad: PadLayout = PadGeometry.layout(1, 1, resources.displayMetrics.density)


    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_INK
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isHapticFeedbackEnabled = true
    }

    // ---------------------------------------------------------------- measurement

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        // Take all the width on offer, wrap_content included: a pad narrower than the
        // window would put the thumb clusters in the wrong place.
        val w = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
            (360f * density).toInt()
        } else {
            View.MeasureSpec.getSize(widthMeasureSpec)
        }
        // With wrap_content height (a control strip under the screen) ask for a band
        // tall enough for a real thumb; with match_parent (overlaid on the screen in
        // landscape) resolveSize just hands back the parent's height.
        val wanted = (w * 0.40f).coerceIn(150f * density, 260f * density).toInt()
        setMeasuredDimension(w, resolveSize(wanted, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        if (width == 0 || height == 0) return
        val cls = if (width.toFloat() / height > 1.5f) "wide" else "square"
        if (cls != layoutClass) {
            layoutClass = cls
            loadLayout(savedLayouts[cls])
            if (savedLayouts[cls].isNullOrEmpty() || cls in redoDefaults) { redoDefaults = redoDefaults - cls; applyDefaults() }
        }
        pad = PadEdit.apply(PadGeometry.layout(width, height, resources.displayMetrics.density * sizeScale),
            tfs, resources.displayMetrics.density, width, height)
    }

    // ------------------------------------------------------------ layout editor

    /** "wide" (folded / cover screen) or "square" (unfolded); each has its own layout. */
    var layoutClass = ""
        private set
    /** Saved layouts per class (see [layoutString]); set by the activity from its prefs. */
    var savedLayouts: Map<String, String> = emptyMap()
    /** Classes whose saved layout gets [applyDefaults] once (migration from 60% for all). */
    var redoDefaults: Set<String> = emptySet()
    /** Called after every edit with (class, layout string) so the activity can save it. */
    var onLayoutEdited: ((String, String) -> Unit)? = null

    private val tfs = java.util.EnumMap<PadGroup, GroupTf>(PadGroup::class.java).apply {
        PadGroup.values().forEach { put(it, GroupTf()) }
    }

    /** While on, touches move/select groups instead of pressing buttons; a grid is drawn. */
    var editMode = false
        set(v) { field = v; if (v) releaseAll(); selected = null; invalidate() }
    var selected: PadGroup? = null
        private set

    fun layoutString(): String = PadGroup.values().joinToString(";") { g ->
        val t = tfs.getValue(g); "${g.name}:${t.dx},${t.dy},${t.scale}"
    }

    private fun loadLayout(str: String?) {
        tfs.values.forEach { it.dx = 0f; it.dy = 0f; it.scale = 1f }
        str?.split(";")?.forEach { part ->
            val kv = part.split(":"); if (kv.size != 2) return@forEach
            val g = runCatching { PadGroup.valueOf(kv[0]) }.getOrNull() ?: return@forEach
            val v = kv[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (v.size == 3) tfs.getValue(g).apply { dx = v[0]; dy = v[1]; scale = v[2].coerceIn(MIN_SCALE, MAX_SCALE) }
        }
    }

    fun resetLayout() {
        tfs.values.forEach { it.dx = 0f; it.dy = 0f; it.scale = 1f }
        relayout(); invalidate(); edited()
        applyDefaults()
    }

    /** Only the top row (rewind, fast-forward, MENU) has its own default size:
     *  60% unfolded ("square"), 70% folded ("wide"). */
    private fun applyDefaults() {
        if (width > 0) setGroupScalePercent(listOf(PadGroup.MENU, PadGroup.REW, PadGroup.FF), if (layoutClass == "square") 60 else 70)
    }

    /** +/- buttons: grow or shrink the selected group by [delta] (factor units). */
    fun scaleSelected(delta: Float) {
        val g = selected ?: return
        val t = tfs.getValue(g)
        t.scale = (Math.round((t.scale + delta) * 10f) / 10f).coerceIn(MIN_SCALE, MAX_SCALE)
        relayout(); invalidate(); edited()
    }

    /** Size of a set of groups in percent (menu "Rozmiar": D-pad, A/B, SELECT/START,
     *  the overlay's own buttons), for the current screen class; same store as the editor. */
    fun groupScalePercent(groups: List<PadGroup>): Int = Math.round(tfs.getValue(groups.first()).scale * 100)

    fun setGroupScalePercent(groups: List<PadGroup>, percent: Int) {
        val v = (percent / 100f).coerceIn(MIN_SCALE, MAX_SCALE)
        // The top row (rewind, fast-forward, MENU) is three groups: scaling each about its
        // own centre would leave the gaps as they are and spread the row out. Move their
        // centres too, proportionally towards MENU (the corner button), so the row shrinks
        // and grows as one piece.
        if (groups.size > 1 && PadGroup.MENU in groups && width > 0) {
            val d = resources.displayMetrics.density
            val base = PadGeometry.layout(width, height, d * sizeScale)
            val cx = mapOf(PadGroup.REW to base.rewCx, PadGroup.FF to base.ffCx, PadGroup.MENU to base.menuCx)
            val anchor = base.menuCx
            // Absolute, not incremental: every press rebuilds the row from MENU's position,
            // so a row already spread out by an older version is repaired too.
            val menu = tfs.getValue(PadGroup.MENU)
            for (g in groups) {
                val t = tfs.getValue(g)
                t.dx = menu.dx + ((cx[g] ?: anchor) - anchor) * (v - 1f) / d
                t.dy = menu.dy
            }
        }
        groups.forEach { tfs.getValue(it).scale = v }
        relayout(); invalidate(); edited()
    }

    private fun edited() = onLayoutEdited?.invoke(layoutClass, layoutString())

    private var dragGroup: PadGroup? = null
    private var dragX0 = 0f
    private var dragY0 = 0f
    private var dragDx0 = 0f
    private var dragDy0 = 0f

    private fun editTouch(event: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x; val y = event.y
                // smallest group under the finger wins (top buttons sit inside nothing else)
                dragGroup = PadGroup.values().filter { PadEdit.bounds(pad, it).contains(x, y) }
                    .minByOrNull { PadEdit.bounds(pad, it).let { r -> r.width() * r.height() } }
                selected = dragGroup ?: selected
                dragGroup?.let { g -> tfs.getValue(g).let { dragDx0 = it.dx; dragDy0 = it.dy } }
                dragX0 = x; dragY0 = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> dragGroup?.let { g ->
                val t = tfs.getValue(g)
                // snap to the grid
                t.dx = Math.round((dragDx0 + (event.x - dragX0) / d) / GRID_DP) * GRID_DP
                t.dy = Math.round((dragDy0 + (event.y - dragY0) / d) / GRID_DP) * GRID_DP
                relayout(); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragGroup != null) edited()
                dragGroup = null
            }
        }
        return true
    }

    private val gridPaint = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f }
    private val gridPaintMajor = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1.5f }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = COLOR_LABEL; strokeWidth = 6f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99FFFFFF.toInt(); strokeWidth = 2f }

    private fun drawEditor(canvas: Canvas, l: PadLayout) {
        val step = GRID_DP * resources.displayMetrics.density
        var i = 0; var x = 0f
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), if (i % 4 == 0) gridPaintMajor else gridPaint); x += step; i++ }
        i = 0; var y = 0f
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, if (i % 4 == 0) gridPaintMajor else gridPaint); y += step; i++ }
        for (g in PadGroup.values()) PadEdit.bounds(l, g).let { r -> canvas.drawRect(r.left, r.top, r.right, r.bottom, if (g == selected) selPaint else boxPaint) }
    }

    // --------------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return editTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> {
                setTouchMask(0)
                if (aimPointer != -1) { aimPointer = -1 }
                return true
            }
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> Unit
            else -> return false
        }

        // Recompute from the *whole* live pointer set every event. Every finger on the
        // glass contributes its own bits, which is what makes "diagonal while firing"
        // work; the pointer being lifted right now is still in the list, so it is
        // skipped by index.
        val lifted = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            else -> -1
        }
        var mask = 0
        val hidden = if (aimMode) GbButtons.A or GbButtons.B or GbButtons.UP or GbButtons.DOWN or
            GbButtons.LEFT or GbButtons.RIGHT else 0
        for (i in 0 until event.pointerCount) {
            if (i == lifted) continue
            val m = PadGeometry.maskAt(pad, event.getX(i), event.getY(i)) and hidden.inv()
            mask = mask or m
            // the first finger that lands on no button aims the light gun
            if (aimMode && m == 0 && (aimPointer == -1 || aimPointer == event.getPointerId(i)) &&
                (event.actionMasked != MotionEvent.ACTION_MOVE || aimPointer != -1)) {
                aimPointer = event.getPointerId(i)
                onAim?.invoke(event.getX(i), event.getY(i), true)
            }
        }
        if (aimMode && aimPointer != -1) {
            val idx = event.findPointerIndex(aimPointer)
            if (idx == -1 || idx == lifted) {
                if (idx != -1) onAim?.invoke(event.getX(idx), event.getY(idx), false)
                aimPointer = -1
            }
        }
        setTouchMask(mask)
        return true
    }

    private fun setTouchMask(mask: Int) {
        if (mask == touchMask) return
        touchMask = mask
        publish()
    }

    // ----------------------------------------------------------- physical gamepad

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.onKeyUp(keyCode, event)

    /**
     * Feed a key event from a physical pad or keyboard. Returns true if it mapped to
     * a Game Boy button. Safe to call from an activity's onKeyDown/onKeyUp.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val bit = keyCodeToButton(event.keyCode)
        if (bit == 0) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val next = if (down) keyMask or bit else keyMask and bit.inv()
        if (next != keyMask) {
            keyMask = next
            // Auto-repeat re-delivers a key that is already down; don't buzz for those.
            if (down && event.repeatCount == 0) tick()
            publish()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        handleMotionEvent(event) || super.onGenericMotionEvent(event)

    /**
     * Handle a joystick motion event: the hat switch that many Bluetooth pads report
     * their D-pad on, plus the left analogue stick. Returns true if it was a joystick
     * event this view consumed.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false

        var x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        var y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (abs(x) < AXIS_THRESHOLD && abs(y) < AXIS_THRESHOLD) {
            x = event.getAxisValue(MotionEvent.AXIS_X)
            y = event.getAxisValue(MotionEvent.AXIS_Y)
        }
        var mask = 0
        if (x <= -AXIS_THRESHOLD) mask = mask or GbButtons.LEFT
        if (x >= AXIS_THRESHOLD) mask = mask or GbButtons.RIGHT
        if (y <= -AXIS_THRESHOLD) mask = mask or GbButtons.UP
        if (y >= AXIS_THRESHOLD) mask = mask or GbButtons.DOWN
        if (mask != axisMask) {
            axisMask = mask
            publish()
        }
        return true
    }

    /** Release everything; call from onPause so a held button does not stick. */
    fun releaseAll() {
        if (touchMask or keyMask or axisMask == 0) return
        touchMask = 0
        keyMask = 0
        axisMask = 0
        publish()
    }

    fun keyCodeToButton(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_SPACE -> GbButtons.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_Z -> GbButtons.B
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> GbButtons.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_DEL -> GbButtons.SELECT
        KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE -> GbButtons.MENU
        KeyEvent.KEYCODE_BUTTON_L1 -> GbButtons.REWIND
        KeyEvent.KEYCODE_BUTTON_R1 -> GbButtons.FFWD
        KeyEvent.KEYCODE_DPAD_UP -> GbButtons.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GbButtons.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GbButtons.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GbButtons.RIGHT
        // Diagonals: some pads report the hat as these compound keycodes.
        KeyEvent.KEYCODE_DPAD_UP_LEFT -> GbButtons.UP or GbButtons.LEFT
        KeyEvent.KEYCODE_DPAD_UP_RIGHT -> GbButtons.UP or GbButtons.RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> GbButtons.DOWN or GbButtons.LEFT
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> GbButtons.DOWN or GbButtons.RIGHT
        else -> 0
    }

    // ------------------------------------------------------------------ plumbing

    private fun publish() {
        val next = touchMask or keyMask or axisMask
        if (next == buttons) {
            invalidate()
            return
        }
        val pressedNow = next and buttons.inv()
        buttons = next
        if (pressedNow != 0) tick()
        invalidate()
        onButtonsChanged?.invoke(next)
    }

    private fun tick() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun down(bit: Int) = buttons and bit != 0

    // ------------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val l = pad
        // Styled after the NES controller artwork (kontroler_nes.svg): light housings,
        // dark cross with triangle arrows, red A/B, dark pills with red labels. The whole
        // pad is composited at OVERLAY_ALPHA so the game stays visible underneath.
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), overlayAlpha)
        if (!aimMode) {
            drawDpad(canvas, l)
            // One light panel joins each pair (B+A, TB+TA) instead of a square per button.
            drawPanel(canvas, l.bCx, l.aCx, l.aCy, l.faceRadius)
            drawFace(canvas, l.bCx, l.bCy, l.faceRadius, "B", down(GbButtons.B))
            drawFace(canvas, l.aCx, l.aCy, l.faceRadius, "A", down(GbButtons.A))
        }
        // SELECT and START share one housing, as on the NES pad.
        pillHousing(canvas, l.selectCx, l.startCx, l.pillCy, l)
        drawPill(canvas, l.selectCx, l.pillCy, l, "SELECT", down(GbButtons.SELECT), labelAbove = true, housing = false)
        drawPill(canvas, l.startCx, l.pillCy, l, "START", down(GbButtons.START), labelAbove = true, housing = false)
        drawTopPill(canvas, l.menuCx, l.menuCy, l.menuS, l, down(GbButtons.MENU)) { cx, cy, h -> drawPillText(canvas, "MENU", cx, cy, h) }
        drawTopPill(canvas, l.ffCx, l.ffCy, l.ffS, l, down(GbButtons.FFWD)) { cx, cy, h -> drawArrows(canvas, cx, cy, h, 1f) }
        drawTopPill(canvas, l.rewCx, l.rewCy, l.rewS, l, down(GbButtons.REWIND)) { cx, cy, h -> drawArrows(canvas, cx, cy, h, -1f) }
        canvas.restoreToCount(save)
        if (editMode) drawEditor(canvas, l)
    }

    private val path = android.graphics.Path()

    /** Plus-shaped path centred on (cx, cy): arms reach [r], half-width [a]. */
    private fun crossPath(cx: Float, cy: Float, r: Float, a: Float): android.graphics.Path {
        path.reset()
        path.moveTo(cx - a, cy - r); path.lineTo(cx + a, cy - r); path.lineTo(cx + a, cy - a)
        path.lineTo(cx + r, cy - a); path.lineTo(cx + r, cy + a); path.lineTo(cx + a, cy + a)
        path.lineTo(cx + a, cy + r); path.lineTo(cx - a, cy + r); path.lineTo(cx - a, cy + a)
        path.lineTo(cx - r, cy + a); path.lineTo(cx - r, cy - a); path.lineTo(cx - a, cy - a)
        path.close()
        return path
    }

    private fun drawDpad(canvas: Canvas, l: PadLayout) {
        val r = l.dpadRadius
        val cx = l.dpadCx
        val cy = l.dpadCy
        val a = r * 0.413f              // SVG: arm 155 of 375
        val inset = r * 0.085f          // SVG: 16 of 187.5
        stroke.color = COLOR_INK
        stroke.strokeJoin = Paint.Join.ROUND
        // light frame
        fill.color = COLOR_HOUSING
        canvas.drawPath(crossPath(cx, cy, r, a), fill)
        stroke.strokeWidth = r * 0.037f
        canvas.drawPath(path, stroke)
        // dark cross
        val ri = r - inset
        val ai = a - inset
        fill.color = COLOR_CROSS
        canvas.drawPath(crossPath(cx, cy, ri, ai), fill)
        // pressed arms: lighter grey, clipped to the dark cross
        if (buttons and DPAD_BITS != 0) {
            canvas.save()
            canvas.clipPath(path)
            fill.color = COLOR_CROSS_LIT
            if (down(GbButtons.UP)) canvas.drawRect(cx - ai, cy - ri, cx + ai, cy - ai * 0.4f, fill)
            if (down(GbButtons.DOWN)) canvas.drawRect(cx - ai, cy + ai * 0.4f, cx + ai, cy + ri, fill)
            if (down(GbButtons.LEFT)) canvas.drawRect(cx - ri, cy - ai, cx - ai * 0.4f, cy + ai, fill)
            if (down(GbButtons.RIGHT)) canvas.drawRect(cx + ai * 0.4f, cy - ai, cx + ri, cy + ai, fill)
            canvas.restore()
        }
        crossPath(cx, cy, ri, ai)
        stroke.strokeWidth = r * 0.032f
        canvas.drawPath(path, stroke)
        // triangle arrows (SVG: tip 0.82r, base 0.61r, half-width 0.17r) and centre dimple
        fun tri(dx: Float, dy: Float, bit: Int) {
            val tip = r * 0.82f; val base = r * 0.61f; val hw = r * 0.17f
            path.reset()
            path.moveTo(cx + dx * tip, cy + dy * tip)
            path.lineTo(cx + dx * base - dy * hw, cy + dy * base - dx * hw)
            path.lineTo(cx + dx * base + dy * hw, cy + dy * base + dx * hw)
            path.close()
            fill.color = if (down(bit)) COLOR_HOUSING else COLOR_ARROW
            canvas.drawPath(path, fill)
        }
        tri(0f, -1f, GbButtons.UP); tri(0f, 1f, GbButtons.DOWN)
        tri(-1f, 0f, GbButtons.LEFT); tri(1f, 0f, GbButtons.RIGHT)
        fill.color = COLOR_ARROW
        canvas.drawCircle(cx, cy, r * 0.17f, fill)
    }

    /** Dark-grey plate under a button pair from [x1] to [x2], wrapping both housings and
     *  the red labels under them. */
    private fun drawPanel(canvas: Canvas, x1: Float, x2: Float, cy: Float, r: Float) {
        val half = r * 1.22f
        val m = r * 0.3f
        val corner = r * 0.29f
        rect.set(min(x1, x2) - half - m, cy - half - m, max(x1, x2) + half + m, cy + half + r * 0.75f)
        fill.color = COLOR_PLATE
        canvas.drawRoundRect(rect, corner, corner, fill)
        stroke.color = COLOR_INK
        stroke.strokeWidth = r * 0.076f
        canvas.drawRoundRect(rect, corner, corner, stroke)
    }

    /** Red button in a light square housing (on the pair's dark plate), red label
     *  under the housing's right corner. */
    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float, text: String, pressed: Boolean) {
        val half = r * 1.22f
        val corner = r * 0.29f
        rect.set(cx - half, cy - half, cx + half, cy + half)
        fill.color = COLOR_HOUSING
        canvas.drawRoundRect(rect, corner, corner, fill)
        stroke.color = COLOR_INK
        stroke.strokeWidth = r * 0.076f
        canvas.drawRoundRect(rect, corner, corner, stroke)
        val sink = if (pressed) r * 0.06f else 0f
        fill.color = if (pressed) COLOR_RED_DARK else COLOR_RED
        canvas.drawCircle(cx, cy + sink, r, fill)
        if (!pressed) {
            // lower crescent (SVG: darker red arc along the bottom of the button)
            canvas.save()
            canvas.clipRect(cx - r, cy, cx + r, cy + r)
            fill.color = COLOR_RED_DARK
            canvas.drawCircle(cx, cy, r * 0.95f, fill)
            fill.color = COLOR_RED
            canvas.drawCircle(cx, cy - r * 0.1f, r * 0.93f, fill)
            canvas.restore()
        }
        canvas.drawCircle(cx, cy + sink, r, stroke)
        label.color = COLOR_LABEL
        label.textSize = r * (if (text.length == 1) 0.56f else 0.5f)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, cx + half, cy + half + label.textSize * 1.05f, label)
        label.textAlign = Paint.Align.CENTER
    }

    /** Top-row button: light housing, dark pill, content drawn inside in light grey. */
    private fun drawTopPill(canvas: Canvas, cx: Float, cy: Float, s: Float, l: PadLayout, pressed: Boolean,
                            content: (Float, Float, Float) -> Unit) {
        val hw = l.topHalfW * s
        val hh = l.topHalfH * s
        val pad = hh * 0.3f
        rect.set(cx - hw - pad, cy - hh - pad, cx + hw + pad, cy + hh + pad)
        fill.color = COLOR_HOUSING
        canvas.drawRoundRect(rect, hh + pad, hh + pad, fill)
        stroke.color = COLOR_INK
        stroke.strokeWidth = max(1f, hh * 0.12f)
        canvas.drawRoundRect(rect, hh + pad, hh + pad, stroke)
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        fill.color = if (pressed) COLOR_CROSS_LIT else COLOR_CROSS
        canvas.drawRoundRect(rect, hh, hh, fill)
        canvas.drawRoundRect(rect, hh, hh, stroke)
        content(cx, cy, hh)
    }

    private fun drawPillText(canvas: Canvas, text: String, cx: Float, cy: Float, hh: Float) {
        label.color = COLOR_HOUSING
        label.textSize = hh * 0.95f
        canvas.drawText(text, cx, cy - (label.ascent() + label.descent()) * 0.5f, label)
    }

    /** Two filled triangles (◄◄ or ►►), [dir] -1 = left, 1 = right. */
    private fun drawArrows(canvas: Canvas, cx: Float, cy: Float, hh: Float, dir: Float) {
        val h = hh * 0.55f            // half height of a triangle
        val w = hh * 0.75f            // triangle width
        fill.color = COLOR_HOUSING
        for (k in 0..1) {
            val baseX = cx - dir * w + dir * k * w   // first triangle behind, second ahead
            path.reset()
            path.moveTo(baseX, cy - h)
            path.lineTo(baseX + dir * w, cy)
            path.lineTo(baseX, cy + h)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    /** Light housing from pill centre [x1] to [x2]. */
    private fun pillHousing(canvas: Canvas, x1: Float, x2: Float, cy: Float, l: PadLayout) {
        val hw = l.pillHalfW
        val hh = l.pillHalfH
        val pad = hh * 0.55f
        rect.set(min(x1, x2) - hw - pad, cy - hh - pad, max(x1, x2) + hw + pad, cy + hh + pad)
        fill.color = COLOR_HOUSING
        canvas.drawRoundRect(rect, hh, hh, fill)
        stroke.color = COLOR_INK
        stroke.strokeWidth = max(1f, hh * 0.18f)
        canvas.drawRoundRect(rect, hh, hh, stroke)
    }

    /** Dark pill (in its own light housing unless [housing] is false); red label above
     *  (SELECT/START) or below. */
    private fun drawPill(canvas: Canvas, cx: Float, cy: Float, l: PadLayout, text: String,
                         pressed: Boolean, labelAbove: Boolean, housing: Boolean = true) {
        val hw = l.pillHalfW
        val hh = l.pillHalfH
        val pad = hh * 0.55f
        if (housing) pillHousing(canvas, cx, cx, cy, l)
        stroke.color = COLOR_INK
        stroke.strokeWidth = max(1f, hh * 0.18f)
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        fill.color = if (pressed) COLOR_CROSS_LIT else COLOR_CROSS
        canvas.drawRoundRect(rect, hh, hh, fill)
        canvas.drawRoundRect(rect, hh, hh, stroke)
        label.color = COLOR_LABEL
        label.textSize = hh * 1.15f
        val y = if (labelAbove) cy - hh - pad - label.textSize * 0.35f
                else cy + hh + pad + label.textSize * 1.05f
        canvas.drawText(text, cx, y, label)
    }

    companion object {
        // Colours are literals rather than R.color lookups so this view stays
        // independent of resource files another agent owns. They match the app's
        // dark palette: slate body, Game Boy green for the active state.
        // From kontroler_nes.svg.
        const val COLOR_HOUSING = 0xFFE3E5E1.toInt()
        const val COLOR_PLATE = 0xFF3D4143.toInt()
        const val COLOR_INK = 0xFF1B1E20.toInt()
        const val COLOR_CROSS = 0xFF303436.toInt()
        const val COLOR_CROSS_LIT = 0xFF5E6366.toInt()
        const val COLOR_ARROW = 0xFF222628.toInt()
        const val COLOR_RED = 0xFFFF3530.toInt()
        const val COLOR_RED_DARK = 0xFFE52421.toInt()
        const val COLOR_LABEL = 0xFFF52B26.toInt()
        const val OVERLAY_ALPHA = 200

        const val DPAD_BITS = GbButtons.UP or GbButtons.DOWN or GbButtons.LEFT or GbButtons.RIGHT

        /** Deflection at which an analogue stick or hat counts as a direction. */
        const val AXIS_THRESHOLD = 0.5f

        /** Layout editor grid step and size range. */
        const val GRID_DP = 8f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.5f
    }
}
