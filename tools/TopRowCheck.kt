import com.dklab.nes.*
fun main() { edgeCheck(); gapMenu()
    for ((w, h, d) in listOf(Triple(2520, 1080, 2.625f), Triple(2184, 1968, 2.625f), Triple(2184, 1968, 2.625f * 1.5f), Triple(2520, 1080, 2.625f * 0.6f))) {
        val l = PadGeometry.layout(w, h, d)
        val r = listOf("MENU" to (l.menuCx to GbButtons.MENU), "FF" to (l.ffCx to GbButtons.FFWD), "REW" to (l.rewCx to GbButtons.REWIND))
        val ok = r.all { (_, p) -> PadGeometry.maskAt(l, p.first, l.topPillCy) == p.second }
        val gap = (l.ffCx - l.rewCx) - 2 * (l.topHalfW + l.pillHitPad)
        println("${w}x$h scale ${"%.2f".format(d / 2.625f)}: centres hit own button=$ok, hit-zone gap REW|FF=${"%.0f".format(gap)} px, REW left edge=${"%.0f".format(l.rewCx - l.topHalfW)}")
    }
}
fun edgeCheck() {
    for ((w, h) in listOf(2520 to 1080, 2184 to 1968)) for (sc in listOf(0.6f, 1f, 1.5f)) {
        val l = PadGeometry.layout(w, h, 2.625f * sc)
        // A housing half 1.22r + plate margin 0.3r must stay inside the screen
        val right = l.aCx + l.faceRadius * 1.52f
        println("${w}x$h scale $sc: A plate right edge ${"%.0f".format(right)} / screen $w -> ${if (right <= w) "OK" else "UCIETE"}")
    }
}
fun gapMenu() { val l = PadGeometry.layout(2520, 1080, 2.625f); println("hit-zone gap FF|MENU=${"%.0f".format((l.menuCx - l.ffCx) - 2 * (l.topHalfW + l.pillHitPad))} px") }
