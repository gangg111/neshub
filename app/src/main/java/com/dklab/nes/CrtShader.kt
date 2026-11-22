package com.dklab.nes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log

/**
 * The "Filtr CRT" on the GPU (Android 13+, AGSL): Timothy Lottes' CRT shader
 * (public domain; libretro/glsl-shaders crt/shaders/crt-lottes.glsl), ported as it is:
 * gaussian scanlines whose beam widens on bright colours, a horizontal gaussian beam,
 * the "stretched VGA" shadow mask on the phone's own pixels, the tube's slight barrel
 * warp, all in linear light. Added: the tube's rounded corners.
 * Its numbers were checked on a frame with a numpy copy (tools/crt_sim.py) at the
 * unfolded Fold's 2184x1968 before being put here.
 */
@android.annotation.TargetApi(33)
class CrtShader(bitmap: Bitmap) {
    private val shader = RuntimeShader(SRC)
    val paint = Paint()
    private var bound: Bitmap? = null

    init { bind(bitmap) }

    /** The picture it reads: the 256 px frame, or the NTSC filter's 602 px one. */
    fun bind(bitmap: Bitmap) {
        if (bitmap === bound) return
        val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        image.filterMode = BitmapShader.FILTER_MODE_NEAREST
        shader.setInputShader("image", image)
        bound = bitmap
        paint.shader = shader
    }

    /** Which part of the bitmap goes where on the surface; [curved] = tube warp and rounded
     *  corners; [bloom] = the original shader's glow around bright parts. */
    fun place(src: Rect, dst: Rect, curved: Boolean, bloom: Boolean) {
        shader.setFloatUniform("curve", if (curved) 1f else 0f)
        shader.setFloatUniform("bloom", if (bloom) 1f else 0f)
        shader.setFloatUniform("srcOrigin", src.left.toFloat(), src.top.toFloat())
        shader.setFloatUniform("srcSize", src.width().toFloat(), src.height().toFloat())
        shader.setFloatUniform("dstOrigin", dst.left.toFloat(), dst.top.toFloat())
        shader.setFloatUniform("dstSize", dst.width().toFloat(), dst.height().toFloat())
        paint.shader = shader
    }

    companion object {
        @android.annotation.TargetApi(33) private fun make(b: Bitmap) = CrtShader(b)

        /** null on Android 12 and older, or if the GPU driver refuses the shader. */
        fun create(bitmap: Bitmap): CrtShader? =
            if (Build.VERSION.SDK_INT < 33) null
            else runCatching { make(bitmap) }.onFailure { Log.e("gbcplayer", "CRT shader", it) }.getOrNull()

        private const val SRC = """
uniform shader image;
uniform float2 srcOrigin;
uniform float2 srcSize;
uniform float2 dstOrigin;
uniform float2 dstSize;
uniform float curve;      // 1 = the tube's warp and rounded corners, 0 = a flat screen
uniform float bloom;      // 1 = crt-lottes' bloom (DO_BLOOM), 0 = off

const float hardScan = -8.0;
const float hardPix = -3.0;
const float warpX = 0.031;
const float warpY = 0.041;
const float maskDark = 0.5;
const float maskLight = 1.5;
const float brightBoost = 1.25;
const float corner = 0.03;
const float hardBloomPix = -1.5;
const float hardBloomScan = -2.0;
const float bloomAmount = 0.15;

float3 toLinear(float3 c) { return pow(clamp(c, 0.0, 1.0), float3(2.2)); }

float3 fetch(float2 pos, float2 off) {
    float2 p = floor(pos * srcSize + off) + 0.5;
    p = clamp(p, float2(0.5), srcSize - 0.5);
    return toLinear(brightBoost * image.eval(srcOrigin + p).rgb);
}

float2 dist(float2 pos) {
    pos = pos * srcSize;
    return -((pos - floor(pos)) - 0.5);
}

float gaus(float pos, float scale) { return exp2(scale * pos * pos); }

float3 horz3(float2 pos, float off) {
    float3 b = fetch(pos, float2(-1.0, off));
    float3 c = fetch(pos, float2(0.0, off));
    float3 d = fetch(pos, float2(1.0, off));
    float dst = dist(pos).x;
    float wb = gaus(dst - 1.0, hardPix);
    float wc = gaus(dst, hardPix);
    float wd = gaus(dst + 1.0, hardPix);
    return (b * wb + c * wc + d * wd) / (wb + wc + wd);
}

float3 horz5(float2 pos, float off) {
    float3 a = fetch(pos, float2(-2.0, off));
    float3 b = fetch(pos, float2(-1.0, off));
    float3 c = fetch(pos, float2(0.0, off));
    float3 d = fetch(pos, float2(1.0, off));
    float3 e = fetch(pos, float2(2.0, off));
    float dst = dist(pos).x;
    float wa = gaus(dst - 2.0, hardPix);
    float wb = gaus(dst - 1.0, hardPix);
    float wc = gaus(dst, hardPix);
    float wd = gaus(dst + 1.0, hardPix);
    float we = gaus(dst + 2.0, hardPix);
    return (a * wa + b * wb + c * wc + d * wd + e * we) / (wa + wb + wc + wd + we);
}

float3 horz7(float2 pos, float off) {
    float3 a = fetch(pos, float2(-3.0, off));
    float3 b = fetch(pos, float2(-2.0, off));
    float3 c = fetch(pos, float2(-1.0, off));
    float3 d = fetch(pos, float2(0.0, off));
    float3 e = fetch(pos, float2(1.0, off));
    float3 f = fetch(pos, float2(2.0, off));
    float3 g = fetch(pos, float2(3.0, off));
    float dst = dist(pos).x;
    float wa = gaus(dst - 3.0, hardBloomPix);
    float wb = gaus(dst - 2.0, hardBloomPix);
    float wc = gaus(dst - 1.0, hardBloomPix);
    float wd = gaus(dst, hardBloomPix);
    float we = gaus(dst + 1.0, hardBloomPix);
    float wf = gaus(dst + 2.0, hardBloomPix);
    float wg = gaus(dst + 3.0, hardBloomPix);
    return (a * wa + b * wb + c * wc + d * wd + e * we + f * wf + g * wg) / (wa + wb + wc + wd + we + wf + wg);
}

float scan(float2 pos, float off) { return gaus(dist(pos).y + off, hardScan); }
float bloomScan(float2 pos, float off) { return gaus(dist(pos).y + off, hardBloomScan); }

float3 bloomOf(float2 pos) {
    return horz5(pos, -2.0) * bloomScan(pos, -2.0) + horz7(pos, -1.0) * bloomScan(pos, -1.0) + horz7(pos, 0.0) * bloomScan(pos, 0.0)
        + horz7(pos, 1.0) * bloomScan(pos, 1.0) + horz5(pos, 2.0) * bloomScan(pos, 2.0);
}

float3 tri(float2 pos) {
    return horz3(pos, -1.0) * scan(pos, -1.0) + horz5(pos, 0.0) * scan(pos, 0.0) + horz3(pos, 1.0) * scan(pos, 1.0);
}

float2 warp(float2 pos) {
    pos = pos * 2.0 - 1.0;
    pos *= float2(1.0 + pos.y * pos.y * warpX * curve, 1.0 + pos.x * pos.x * warpY * curve);
    return pos * 0.5 + 0.5;
}

float3 mask(float2 fc) {
    float3 m = float3(maskDark);
    float x = fract((fc.x + fc.y * 3.0) / 6.0);
    if (x < 0.333) m.r = maskLight;
    else if (x < 0.666) m.g = maskLight;
    else m.b = maskLight;
    return m;
}

half4 main(float2 fc) {
    float2 pos = warp((fc - dstOrigin) / dstSize);
    if (pos.x <= 0.0 || pos.x >= 1.0 || pos.y <= 0.0 || pos.y >= 1.0) return half4(0.0, 0.0, 0.0, 1.0);
    float3 c = tri(pos);
    if (bloom > 0.5) c += bloomOf(pos) * bloomAmount;
    c *= mask(floor(fc));
    if (curve > 0.5) {
        float2 k = max(float2(corner) - min(pos, 1.0 - pos), 0.0);
        c *= clamp((corner - length(k)) / 0.004, 0.0, 1.0);
    }
    return half4(half3(pow(clamp(c, 0.0, 1.0), float3(1.0 / 2.2))), 1.0);
}
"""
    }
}
