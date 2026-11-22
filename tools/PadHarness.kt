// Host-side harness for ControlPad.kt's touch geometry. Not part of the APK.
//
// Run it (both commands from this directory):
//   kotlinc -nowarn -cp ~/android-sdk/platforms/android-34/android.jar \
//       ../app/src/main/java/com/dklab/nes/ControlPad.kt PadHarness.kt -d padout
//   java -cp padout:/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar PadHarnessKt
//
// It compiles the REAL ControlPad.kt against android.jar and calls the same
// PadGeometry the view calls; nothing here re-implements the logic.
import com.dklab.nes.GbButtons
import com.dklab.nes.PadGeometry
import kotlin.math.cos
import kotlin.math.sin

/**
 * Host-side harness. It compiles the REAL ControlPad.kt (against android.jar) and
 * calls the same PadGeometry functions the view calls at runtime -- no copies, no
 * re-implementation. PadGeometry touches no Android class, so it runs on a desktop
 * JVM unchanged.
 */

private var failures = 0

private fun check(label: String, got: Int, want: Int) {
    val ok = got == want
    if (!ok) failures++
    println(
        String.format(
            "  %-38s mask=0x%02X %-18s %s",
            label, got, GbButtons.describe(got), if (ok) "OK" else "FAIL (wanted " + GbButtons.describe(want) + ")"
        )
    )
}

fun main() {
    // A 2400x1080 landscape window at density 2.75 (a typical modern phone).
    val w = 2400
    val h = 1080
    val density = 2.75f
    val l = PadGeometry.layout(w, h, density)

    println("View ${w}x${h} @ density $density")
    println(
        String.format(
            "  D-pad centre (%.0f, %.0f)  drawn r=%.0f  hit r=%.0f  dead=%.0f",
            l.dpadCx, l.dpadCy, l.dpadRadius, l.dpadHitRadius, l.dpadDeadZone
        )
    )
    println(
        String.format(
            "  A (%.0f, %.0f)  B (%.0f, %.0f)  r=%.0f  hit r=%.0f",
            l.aCx, l.aCy, l.bCx, l.bCy, l.faceRadius, l.faceHitRadius
        )
    )
    println(
        String.format(
            "  SELECT x=%.0f  START x=%.0f  y=%.0f  half=%.0fx%.0f  pad=%.0f",
            l.selectCx, l.startCx, l.pillCy, l.pillHalfW, l.pillHalfH, l.pillHitPad
        )
    )

    // ---- 1. Sweep the D-pad through 360 degrees at 15 degree steps -------------
    println("\n1) D-pad angle sweep at 0.7 * hit radius (screen y grows downwards)")
    val r = l.dpadHitRadius * 0.7f
    var deg = 0
    while (deg < 360) {
        val rad = Math.toRadians(deg.toDouble())
        val x = l.dpadCx + (r * cos(rad)).toFloat()
        val y = l.dpadCy - (r * sin(rad)).toFloat()   // minus: maths angle -> screen
        val expect = when (deg) {
            0, 345, 15 -> GbButtons.RIGHT
            30, 45, 60 -> GbButtons.RIGHT or GbButtons.UP
            75, 90, 105 -> GbButtons.UP
            120, 135, 150 -> GbButtons.UP or GbButtons.LEFT
            165, 180, 195 -> GbButtons.LEFT
            210, 225, 240 -> GbButtons.LEFT or GbButtons.DOWN
            255, 270, 285 -> GbButtons.DOWN
            else -> GbButtons.DOWN or GbButtons.RIGHT
        }
        check(String.format("%3d deg", deg), PadGeometry.maskAt(l, x, y), expect)
        deg += 15
    }

    // ---- 2. The headline case: a finger literally between up and left ----------
    println("\n2) A touch placed BETWEEN up and left must set BOTH bits")
    val diag = (l.dpadHitRadius * 0.6f / Math.sqrt(2.0)).toFloat()
    check(
        String.format("(cx-%.0f, cy-%.0f) up-left", diag, diag),
        PadGeometry.maskAt(l, l.dpadCx - diag, l.dpadCy - diag),
        GbButtons.UP or GbButtons.LEFT
    )
    check(
        String.format("(cx+%.0f, cy-%.0f) up-right", diag, diag),
        PadGeometry.maskAt(l, l.dpadCx + diag, l.dpadCy - diag),
        GbButtons.UP or GbButtons.RIGHT
    )
    check(
        String.format("(cx-%.0f, cy+%.0f) down-left", diag, diag),
        PadGeometry.maskAt(l, l.dpadCx - diag, l.dpadCy + diag),
        GbButtons.DOWN or GbButtons.LEFT
    )
    check(
        String.format("(cx+%.0f, cy+%.0f) down-right", diag, diag),
        PadGeometry.maskAt(l, l.dpadCx + diag, l.dpadCy + diag),
        GbButtons.DOWN or GbButtons.RIGHT
    )

    // ---- 3. Dead zone and outside the pad -------------------------------------
    println("\n3) Dead zone and misses")
    check("dead centre", PadGeometry.maskAt(l, l.dpadCx, l.dpadCy), 0)
    check(
        "just inside dead zone",
        PadGeometry.maskAt(l, l.dpadCx + l.dpadDeadZone * 0.9f, l.dpadCy), 0
    )
    check(
        "just outside dead zone -> RIGHT",
        PadGeometry.maskAt(l, l.dpadCx + l.dpadDeadZone * 1.1f, l.dpadCy), GbButtons.RIGHT
    )
    check(
        "just outside hit radius",
        PadGeometry.maskAt(l, l.dpadCx + l.dpadHitRadius * 1.05f, l.dpadCy), 0
    )
    check("empty middle of the view", PadGeometry.maskAt(l, w / 2f, h * 0.3f), 0)

    // ---- 4. Generous hit area: art edge vs hit edge ----------------------------
    println("\n4) Hit areas are bigger than the drawn art")
    check(
        String.format("D-pad %.0fpx past the drawn arm", l.dpadHitRadius - l.dpadRadius),
        PadGeometry.maskAt(l, l.dpadCx - (l.dpadRadius + l.dpadHitRadius) / 2f, l.dpadCy),
        GbButtons.LEFT
    )
    check(
        String.format("A %.0fpx past the drawn circle", l.faceHitRadius - l.faceRadius),
        PadGeometry.maskAt(l, l.aCx + (l.faceRadius + l.faceHitRadius) / 2f, l.aCy),
        GbButtons.A
    )
    check(
        "SELECT one pad-width left of the pill",
        PadGeometry.maskAt(l, l.selectCx - l.pillHalfW - l.pillHitPad * 0.8f, l.pillCy),
        GbButtons.SELECT
    )

    // ---- 5. Face buttons and pills --------------------------------------------
    println("\n5) Face buttons, START and SELECT")
    check("centre of A", PadGeometry.maskAt(l, l.aCx, l.aCy), GbButtons.A)
    check("centre of B", PadGeometry.maskAt(l, l.bCx, l.bCy), GbButtons.B)
    check("centre of START", PadGeometry.maskAt(l, l.startCx, l.pillCy), GbButtons.START)
    check("centre of SELECT", PadGeometry.maskAt(l, l.selectCx, l.pillCy), GbButtons.SELECT)
    check(
        "A right of B, same level (NES pad)",
        if (l.aCx > l.bCx && l.aCy == l.bCy) 1 else 0, 1
    )

    // ---- 6. Multitouch: what onTouchEvent actually does with N pointers --------
    // onTouchEvent ORs PadGeometry.maskAt over every live pointer; this reproduces
    // that fold over synthetic pointer sets.
    println("\n6) Multitouch: OR of every live pointer, exactly as onTouchEvent folds them")
    fun multi(name: String, points: List<Pair<Float, Float>>, want: Int) {
        var m = 0
        for ((x, y) in points) m = m or PadGeometry.maskAt(l, x, y)
        check(name, m, want)
    }
    multi(
        "up-left thumb + A thumb (shmup: diagonal while firing)",
        listOf(l.dpadCx - diag to l.dpadCy - diag, l.aCx to l.aCy),
        GbButtons.UP or GbButtons.LEFT or GbButtons.A
    )
    multi(
        "down-right + A + B (three fingers)",
        listOf(
            l.dpadCx + diag to l.dpadCy + diag,
            l.aCx to l.aCy,
            l.bCx to l.bCy
        ),
        GbButtons.DOWN or GbButtons.RIGHT or GbButtons.A or GbButtons.B
    )
    multi(
        "START + SELECT together (soft reset combos)",
        listOf(l.startCx to l.pillCy, l.selectCx to l.pillCy),
        GbButtons.START or GbButtons.SELECT
    )
    multi(
        "second finger lands on empty glass: first still held",
        listOf(l.dpadCx - diag to l.dpadCy - diag, w * 0.5f to h * 0.2f),
        GbButtons.UP or GbButtons.LEFT
    )

    // ---- 7. Bit order matches binjgb's joypad_pack_buttons ---------------------
    println("\n7) Bit order (binjgb joypad_pack_buttons)")
    val order = listOf(
        "A" to GbButtons.A, "B" to GbButtons.B, "SELECT" to GbButtons.SELECT,
        "START" to GbButtons.START, "RIGHT" to GbButtons.RIGHT, "LEFT" to GbButtons.LEFT,
        "UP" to GbButtons.UP, "DOWN" to GbButtons.DOWN
    )
    order.forEachIndexed { i, (n, v) ->
        check("$n is bit $i", v, 1 shl i)
    }

    // ---- 8. A short control strip (wrap_content under the screen) still works --
    println("\n8) Same logic in a 1080x400 control strip (portrait band)")
    val strip = PadGeometry.layout(1080, 400, density)
    val sd = (strip.dpadHitRadius * 0.6f / Math.sqrt(2.0)).toFloat()
    check(
        "up-left in the strip",
        PadGeometry.maskAt(strip, strip.dpadCx - sd, strip.dpadCy - sd),
        GbButtons.UP or GbButtons.LEFT
    )
    check("A in the strip", PadGeometry.maskAt(strip, strip.aCx, strip.aCy), GbButtons.A)
    check(
        "SELECT in the strip is not eaten by the D-pad",
        PadGeometry.maskAt(strip, strip.selectCx, strip.pillCy), GbButtons.SELECT
    )

    // ---- 9. Hit map: every cell is a real PadGeometry.maskAt() call ------------
    println("\n9) Hit map of the 2400x1080 landscape view, sampled from the real maskAt()")
    println("   . none   < left  > right  ^ up  v down  7 up-left  9 up-right  1 down-left  3 down-right")
    println("   A = A    B = B    s = SELECT   S = START")
    val cols = 100
    val rows = 26
    for (row in 0 until rows) {
        val y = (row + 0.5f) * h / rows
        val sb = StringBuilder("   ")
        for (col in 0 until cols) {
            val x = (col + 0.5f) * w / cols
            sb.append(
                when (PadGeometry.maskAt(l, x, y)) {
                    0 -> '.'
                    GbButtons.LEFT -> '<'
                    GbButtons.RIGHT -> '>'
                    GbButtons.UP -> '^'
                    GbButtons.DOWN -> 'v'
                    GbButtons.UP or GbButtons.LEFT -> '7'
                    GbButtons.UP or GbButtons.RIGHT -> '9'
                    GbButtons.DOWN or GbButtons.LEFT -> '1'
                    GbButtons.DOWN or GbButtons.RIGHT -> '3'
                    GbButtons.A -> 'A'
                    GbButtons.B -> 'B'
                    GbButtons.SELECT -> 's'
                    GbButtons.START -> 'S'
                    else -> '?'
                }
            )
        }
        println(sb)
    }

    println("\n" + if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
