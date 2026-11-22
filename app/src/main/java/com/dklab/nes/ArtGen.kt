package com.dklab.nes

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * A cartridge's art made from the player's own dump: the hub ships recipes
 * (assets/hub/art.json), never pictures of the games. The NES is deterministic, so the
 * same ROM and the same buttons give the same frames on every phone (tools/artgen.c
 * runs the same recipe on a PC; that is how the recipes were chosen).
 *
 * Recipe: "shot" = frame (no buttons pressed) shown as the title screen; "logo" =
 * [x, y, w, h] cut from that frame, 3x, its background colour (the top-left pixel)
 * transparent, only where connected to the edge when "flood", plus "clear" rects;
 * "script" = buttons ("mask:frames,...", hex GBC_BUTTON_* mask, '*' = repeat from here)
 * played for the clip; "clip" = its first frame; 60 frames, every 6th (10 fps, 6 s).
 */
object ArtGen {
    private const val W = Emulator.SCREEN_WIDTH
    private const val H = Emulator.SCREEN_HEIGHT
    private const val CLIP_FRAMES = 60
    private const val CLIP_EVERY = 6

    /** {logo, shot, anim} as data: URIs, or null when [recipe] cannot be played. */
    fun make(rom: ByteArray, recipe: JSONObject): JSONObject? {
        // the player's picture settings live in the core: art is drawn with the defaults
        NativeBridge.nativeSetOption("fceumm_palette", "default")
        NativeBridge.nativeSetOption("fceumm_ntsc_filter", "disabled")
        NativeBridge.nativeDetectGame(rom)
        val shotAt = recipe.getInt("shot")
        val shot = run(rom, "0:1", listOf(shotAt)).firstOrNull() ?: return null
        val out = JSONObject().put("shot", dataUri("png", png(shot)))
        recipe.optJSONArray("logo")?.let { r ->
            out.put("logo", dataUri("png", png(logo(shot, IntArray(4) { r.getInt(it) },
                recipe.optBoolean("flood"), recipe.optJSONArray("clear")), 3 * r.getInt(2), 3 * r.getInt(3))))
        }
        if (recipe.has("script")) {
            val from = recipe.getInt("clip")
            val frames = run(rom, recipe.getString("script"), List(CLIP_FRAMES) { from + it * CLIP_EVERY })
            if (frames.size == CLIP_FRAMES) out.put("anim", dataUri("png", apng(frames.map { png(it) }, 100)))
        }
        return out
    }

    /** Plays [script] and returns copies of the frames numbered [at] (0-based, ascending). */
    private fun run(rom: ByteArray, script: String, at: List<Int>): List<IntArray> {
        val buttons = buttonsOf(script)
        val emu = Emulator.open(rom) ?: return emptyList()
        try {
            val px = IntArray(Emulator.SCREEN_PIXELS)
            val res = ArrayList<IntArray>()
            var next = 0
            for (f in 0..(at.lastOrNull() ?: return res)) {
                emu.setButtons(buttons(f))
                emu.runFrame(px, null)
                if (f == at[next]) { res += px.copyOf(); next++ }
            }
            return res
        } finally { emu.close() }
    }

    /** The buttons held on each frame (0-based) of [script]; as tools/artgen.c. */
    internal fun buttonsOf(script: String): (Int) -> Int {
        val segs = ArrayList<Pair<Int, Int>>()   // (buttons, frames)
        var loopAt = -1
        for (part in script.split(',')) {
            var s = part.trim(); if (s.isEmpty()) continue
            if (s.startsWith("*")) { loopAt = segs.size; s = s.substring(1) }
            val (m, n) = s.split(':')
            segs += m.toInt(16) to n.toInt()
        }
        val head = segs.take(maxOf(loopAt, 0)).sumOf { it.second }
        val loop = if (loopAt < 0) emptyList() else segs.drop(loopAt)
        val len = loop.sumOf { it.second }
        return fun(f: Int): Int {
            var t = 0
            for ((b, n) in segs) { if (f < t + n) return b; t += n }
            if (len <= 0) return 0
            var r = (f - head) % len
            for ((b, n) in loop) { if (r < n) return b; r -= n }
            return 0
        }
    }

    private fun logo(frame: IntArray, r: IntArray, flood: Boolean, clear: org.json.JSONArray?): IntArray {
        val (x0, y0, w, h) = r.toList()
        val c = IntArray(w * h) { frame[(y0 + it / w) * W + x0 + it % w] }
        val bg = c[0]
        val clearPx = BooleanArray(w * h)
        if (flood) {
            val stack = ArrayDeque<Int>()
            for (x in 0 until w) { stack += x; stack += (h - 1) * w + x }
            for (y in 0 until h) { stack += y * w; stack += y * w + w - 1 }
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (clearPx[i] || c[i] != bg) continue
                clearPx[i] = true
                val x = i % w; val y = i / w
                if (x > 0) stack += i - 1; if (x < w - 1) stack += i + 1
                if (y > 0) stack += i - w; if (y < h - 1) stack += i + w
            }
        } else for (i in c.indices) if (c[i] == bg) clearPx[i] = true
        if (clear != null) for (k in 0 until clear.length()) {
            val q = clear.getJSONArray(k)
            for (y in q.getInt(1) until minOf(h, q.getInt(1) + q.getInt(3)))
                for (x in q.getInt(0) until minOf(w, q.getInt(0) + q.getInt(2))) clearPx[y * w + x] = true
        }
        // 3x, nearest neighbour
        return IntArray(9 * w * h) { i ->
            val s = (i / (3 * w)) / 3 * w + (i % (3 * w)) / 3
            if (clearPx[s]) 0 else c[s] or 0xFF000000.toInt()
        }
    }

    /** Frames are opaque (an RGB PNG, smaller); a logo keeps its alpha. */
    private fun png(px: IntArray, w: Int = W, h: Int = H): ByteArray {
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (w == W && h == H) bmp.setHasAlpha(false)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }

    /** Animated PNG from same-sized PNGs: frame 1's IDAT, the others' as fdAT. */
    internal fun apng(pngs: List<ByteArray>, delayMs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pngs[0], 0, 8)
        var seq = 0
        fun chunk(type: String, data: ByteArray) {
            val t = type.toByteArray(Charsets.US_ASCII)
            out.write(ByteBuffer.allocate(4).putInt(data.size).array()); out.write(t); out.write(data)
            val crc = CRC32().apply { update(t); update(data) }.value.toInt()
            out.write(ByteBuffer.allocate(4).putInt(crc).array())
        }
        fun chunks(png: ByteArray) = sequence {
            var p = 8
            while (p + 8 <= png.size) {
                val len = ByteBuffer.wrap(png, p, 4).int
                yield(String(png, p + 4, 4, Charsets.US_ASCII) to png.copyOfRange(p + 8, p + 8 + len))
                p += 12 + len
            }
        }
        chunk("IHDR", chunks(pngs[0]).first { it.first == "IHDR" }.second)
        chunk("acTL", ByteBuffer.allocate(8).putInt(pngs.size).putInt(0).array())
        pngs.forEachIndexed { i, png ->
            chunk("fcTL", ByteBuffer.allocate(26).putInt(seq++).putInt(W).putInt(H).putInt(0).putInt(0)
                .putShort(delayMs.toShort()).putShort(1000).put(0).put(0).array())
            for ((type, data) in chunks(png)) if (type == "IDAT") {
                if (i == 0) chunk("IDAT", data)
                else chunk("fdAT", ByteBuffer.allocate(4 + data.size).putInt(seq++).put(data).array())
            }
        }
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun dataUri(type: String, bytes: ByteArray) =
        "data:image/$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
