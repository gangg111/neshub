package com.dklab.nes

import java.util.zip.CRC32

/**
 * ROM patches (translations, fixes): IPS and BPS, applied in memory to the clean ROM
 * before the game starts. Errors are IllegalArgumentException with a message for the user.
 */
object Patches {

    fun isPatch(b: ByteArray) = startsWith(b, "PATCH") || startsWith(b, "BPS1")

    fun apply(rom: ByteArray, patch: ByteArray): ByteArray = when {
        startsWith(patch, "PATCH") -> ips(rom, patch)
        startsWith(patch, "BPS1") -> bps(rom, patch)
        else -> throw IllegalArgumentException("to nie jest łatka IPS ani BPS")
    }

    private fun startsWith(b: ByteArray, magic: String) =
        b.size >= magic.length && magic.indices.all { b[it] == magic[it].code.toByte() }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** IPS: records (offset 24-bit, size 16-bit, data | RLE), "EOF", optional truncate size. */
    private fun ips(rom: ByteArray, p: ByteArray): ByteArray {
        var out = rom.copyOf()
        var i = 5
        fun need(n: Int) { if (i + n > p.size) throw IllegalArgumentException("łatka IPS jest ucięta") }
        while (true) {
            need(3)
            if (u(p, i) == 'E'.code && u(p, i + 1) == 'O'.code && u(p, i + 2) == 'F'.code) { i += 3; break }
            val off = (u(p, i) shl 16) or (u(p, i + 1) shl 8) or u(p, i + 2)
            need(5)
            val n = (u(p, i + 3) shl 8) or u(p, i + 4); i += 5
            if (n == 0) {
                need(3)
                val run = (u(p, i) shl 8) or u(p, i + 1); val v = p[i + 2]; i += 3
                if (off + run > out.size) out = out.copyOf(off + run)
                java.util.Arrays.fill(out, off, off + run, v)
            } else {
                need(n)
                if (off + n > out.size) out = out.copyOf(off + n)
                System.arraycopy(p, i, out, off, n); i += n
            }
        }
        if (i + 3 <= p.size) out = out.copyOf((u(p, i) shl 16) or (u(p, i + 1) shl 8) or u(p, i + 2))
        return out
    }

    /** BPS (beat): checks the source CRC, so a patch for another ROM version is refused. */
    private fun bps(src: ByteArray, p: ByteArray): ByteArray {
        if (p.size < 4 + 12) throw IllegalArgumentException("łatka BPS jest ucięta")
        val end = p.size - 12
        fun crcAt(i: Int) = (u(p, i).toLong()) or (u(p, i + 1).toLong() shl 8) or (u(p, i + 2).toLong() shl 16) or (u(p, i + 3).toLong() shl 24)
        val crc = CRC32()
        crc.update(p, 0, p.size - 4)
        if (crc.value != crcAt(p.size - 4)) throw IllegalArgumentException("łatka BPS jest uszkodzona")
        crc.reset(); crc.update(src)
        if (crc.value != crcAt(p.size - 12)) throw IllegalArgumentException("łatka jest do innej wersji tej gry")
        var i = 4
        fun num(): Long {
            var data = 0L; var shift = 1L
            while (true) {
                if (i >= end) throw IllegalArgumentException("łatka BPS jest uszkodzona")
                val x = u(p, i++)
                data += (x and 0x7F) * shift
                if (x and 0x80 != 0) return data
                shift = shift shl 7; data += shift
            }
        }
        val srcSize = num(); val dstSize = num(); val meta = num()
        if (srcSize != src.size.toLong() || dstSize > 64L * 1024 * 1024) throw IllegalArgumentException("łatka jest do innej wersji tej gry")
        i += meta.toInt()
        val dst = ByteArray(dstSize.toInt())
        var out = 0; var srcRel = 0; var dstRel = 0
        while (i < end) {
            val d = num(); val len = ((d shr 2) + 1).toInt()
            if (out + len > dst.size) throw IllegalArgumentException("łatka BPS jest uszkodzona")
            when ((d and 3).toInt()) {
                0 -> { System.arraycopy(src, out, dst, out, len); out += len }
                1 -> { System.arraycopy(p, i, dst, out, len); i += len; out += len }
                2 -> { val o = num(); srcRel += ((if (o and 1L != 0L) -1 else 1) * (o shr 1)).toInt()
                       System.arraycopy(src, srcRel, dst, out, len); srcRel += len; out += len }
                else -> { val o = num(); dstRel += ((if (o and 1L != 0L) -1 else 1) * (o shr 1)).toInt()
                          repeat(len) { dst[out++] = dst[dstRel++] } }   // may overlap: byte by byte
            }
        }
        crc.reset(); crc.update(dst)
        if (crc.value != crcAt(p.size - 8)) throw IllegalArgumentException("wynik łatki się nie zgadza")
        return dst
    }
}
