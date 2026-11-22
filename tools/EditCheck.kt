import com.dklab.nes.*
fun main() {
    val d = 2.625f; val w = 2520; val h = 1080
    val base = PadGeometry.layout(w, h, d)
    val tfs = PadGroup.values().associateWith { GroupTf() }.toMutableMap()
    // move A/B 100 dp left and 40 dp up, 1.5x bigger
    tfs[PadGroup.AB] = GroupTf(-100f, -40f, 1.5f)
    // drag MENU far off the right/top edge: must be clamped back on screen
    tfs[PadGroup.MENU] = GroupTf(500f, -300f, 1f)
    // shrink D-pad
    tfs[PadGroup.DPAD] = GroupTf(24f, 0f, 0.6f)
    val l = PadEdit.apply(base, tfs, d, w, h)
    fun at(x: Float, y: Float) = GbButtons.describe(PadGeometry.maskAt(l, x, y))
    println("A at its new centre -> ${at(l.aCx, l.aCy)}, B -> ${at(l.bCx, l.bCy)}")
    println("old A centre now -> ${at(base.aCx, base.aCy)} (expected - )")
    println("A radius ${"%.0f".format(base.faceRadius)} -> ${"%.0f".format(l.faceRadius)} (x1.5), moved by ${"%.0f".format((l.aCx + l.bCx)/2 - (base.aCx + base.bCx)/2)} px (= -100 dp = ${"%.0f".format(-100 * d)})")
    val m = PadEdit.bounds(l, PadGroup.MENU)
    println("MENU dragged off-screen -> bounds ${m.left.toInt()}..${m.right.toInt()} x ${m.top.toInt()}..${m.bottom.toInt()} within ${w}x$h: ${m.left >= 0 && m.right <= w && m.top >= 0 && m.bottom <= h}; hit -> ${at(l.menuCx, l.menuCy)}")
    println("D-pad up at new place -> ${at(l.dpadCx, l.dpadCy - l.dpadRadius * 0.7f)}, radius ${"%.0f".format(base.dpadRadius)} -> ${"%.0f".format(l.dpadRadius)}")
    val all = PadGroup.values().all { g -> PadEdit.bounds(l, g).let { it.left >= -0.5f && it.right <= w + 0.5f && it.top >= -0.5f && it.bottom <= h + 0.5f } }
    println("all groups on screen: $all")
}
