#!/usr/bin/env python3
"""Press Start 2P (latin-ext woff2 in hub/fonts) draws a/e/A/E with ogonek with the
letter lifted above the baseline, so Polish words look uneven. Rebuild ą ę Ą Ę from the
font's own plain a e A E (latin woff2) plus a two-pixel ogonek below the baseline.
Re-running it gives the same result."""
import glob, os
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.boundsPen import BoundsPen

FONTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "hub", "fonts")
files = glob.glob(os.path.join(FONTS, "pressstart2p*.woff2"))
ext = [f for f in files if ord("ę") in TTFont(f).getBestCmap()][0]
lat = [f for f in files if f != ext][0]
base = TTFont(lat); bcmap, bgs = base.getBestCmap(), base.getGlyphSet()
font = TTFont(ext); cmap, gs = font.getBestCmap(), font.getGlyphSet()
PX = 125   # one pixel of the 8x8 design (1000 units per em)
for ch, plain in (("ę", "e"), ("ą", "a"), ("Ę", "E"), ("Ą", "A")):
    src = bcmap[ord(plain)]
    rec = RecordingPen(); bgs[src].draw(rec)
    bp = BoundsPen(bgs); bgs[src].draw(bp); x0, y0, x1, y1 = bp.bounds
    pen = TTGlyphPen(gs)
    rec.replay(pen)
    def px(x, y):          # one pixel square, clockwise like the font's outlines
        pen.moveTo((x, y)); pen.lineTo((x, y + PX)); pen.lineTo((x + PX, y + PX)); pen.lineTo((x + PX, y)); pen.closePath()
    right = x1 - PX        # the letter's rightmost pixel column
    px(right - PX, y0 - PX)        # under the letter
    px(right, y0 - 2 * PX)         # and curling right
    font["glyf"][cmap[ord(ch)]] = pen.glyph()
    font["hmtx"][cmap[ord(ch)]] = base["hmtx"][src]
    print(ch, "rebuilt from", plain, "bounds", bp.bounds)
font.save(ext)

# Ż Ź Ć Ś Ń: the font squeezes the capital into 6 pixel rows to fit the accent inside the
# em square, so they look like small letters next to the full-height capitals. Rebuild
# them from the full Z C S N with the accent one pixel row above the letter.
font = TTFont(ext); cmap, gs = font.getBestCmap(), font.getGlyphSet()
for ch, plain, mark in (("Ż", "Z", "dot"), ("Ź", "Z", "acute"), ("Ć", "C", "acute"), ("Ś", "S", "acute"), ("Ń", "N", "acute")):
    src = bcmap[ord(plain)]
    rec = RecordingPen(); bgs[src].draw(rec)
    bp = BoundsPen(bgs); bgs[src].draw(bp); x0, y0, x1, y1 = bp.bounds
    pen = TTGlyphPen(gs); rec.replay(pen)
    def px(x, y):
        pen.moveTo((x, y)); pen.lineTo((x, y + PX)); pen.lineTo((x + PX, y + PX)); pen.lineTo((x + PX, y)); pen.closePath()
    mid = 3 * PX                     # columns 3-4 of the 7-pixel letter
    if mark == "dot": px(mid, y1 + PX); px(mid + PX, y1 + PX)
    else: px(mid, y1 + PX); px(mid + PX, y1 + 2 * PX)
    font["glyf"][cmap[ord(ch)]] = pen.glyph()
    font["hmtx"][cmap[ord(ch)]] = base["hmtx"][src]
    print(ch, "rebuilt from", plain, "+", mark)
font.save(ext)

# Ł (latin-ext file) from L with a two-pixel stroke across the stem, Ó (it lives in the
# latin file) from O with the acute above, both full height like the other capitals.
font = TTFont(ext); cmap, gs = font.getBestCmap(), font.getGlyphSet()
def rebuild(fnt, cm, glyphs, ch, plain, extra):
    src = bcmap[ord(plain)]
    rec = RecordingPen(); bgs[src].draw(rec)
    bp = BoundsPen(bgs); bgs[src].draw(bp)
    pen = TTGlyphPen(glyphs); rec.replay(pen)
    for x, y in extra(bp.bounds):
        pen.moveTo((x, y)); pen.lineTo((x, y + PX)); pen.lineTo((x + PX, y + PX)); pen.lineTo((x + PX, y)); pen.closePath()
    fnt["glyf"][cm[ord(ch)]] = pen.glyph()
    fnt["hmtx"][cm[ord(ch)]] = base["hmtx"][src]
    print(ch, "rebuilt from", plain)
rebuild(font, cmap, gs, "Ł", "L", lambda b: [(b[0] + 2 * PX, 5 * PX), (b[0] - PX, 3 * PX)])
font.save(ext)
lt = TTFont(lat); lcm, lgs = lt.getBestCmap(), lt.getGlyphSet()
rebuild(lt, lcm, lgs, "Ó", "O", lambda b: [(3 * PX, b[3] + PX), (4 * PX, b[3] + 2 * PX)])
lt.save(lat)
