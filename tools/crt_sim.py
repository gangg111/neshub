# numpy copy of the AGSL CRT shader (crt-lottes, public domain, Timothy Lottes) for previews
import sys, numpy as np
from PIL import Image
src = np.asarray(Image.open(sys.argv[1]).convert('RGB'), dtype=np.float32) / 255.0
H, W = src.shape[:2]
crop = 8
srcW, srcH = W - 2 * crop, H
dw, dh = int(sys.argv[3]), int(sys.argv[4])
P = dict(hardScan=-8.0, hardPix=-3.0, warpX=0.031, warpY=0.041, maskDark=0.5, maskLight=1.5, bright=1.25, corner=0.03)
for kv in sys.argv[5:]:
    k, v = kv.split('='); P[k] = float(v)
lin = np.power(np.clip(src * P['bright'], 0, 1), 2.2)
yy, xx = np.mgrid[0:dh, 0:dw].astype(np.float32) + 0.5
u, v = xx / dw, yy / dh
# warp
px, py = u * 2 - 1, v * 2 - 1
px2 = px * (1 + py * py * P['warpX']); py2 = py * (1 + px * px * P['warpY'])
u, v = px2 * 0.5 + 0.5, py2 * 0.5 + 0.5
inside = (u > 0) & (u < 1) & (v > 0) & (v < 1)
sx, sy = u * srcW, v * srcH
dx = -((sx - np.floor(sx)) - 0.5); dy = -((sy - np.floor(sy)) - 0.5)
def fetch(ox, oy):
    x = np.clip(np.floor(sx + ox), 0, srcW - 1).astype(int) + crop
    y = np.clip(np.floor(sy + oy), 0, srcH - 1).astype(int)
    return lin[y, x]
g = lambda d, s: np.exp2(s * d * d)
def horz(oy, n):
    acc = 0; wsum = 0
    for k in range(-(n // 2), n // 2 + 1):
        w = g(dx + k, P['hardPix'])[..., None]; acc = acc + fetch(k, oy) * w; wsum = wsum + w
    return acc / wsum
c = horz(-1, 3) * g(dy - 1, P['hardScan'])[..., None] + horz(0, 5) * g(dy, P['hardScan'])[..., None] + horz(1, 3) * g(dy + 1, P['hardScan'])[..., None]
# stretched VGA shadow mask (type 3) on screen pixels
mx = np.fmod((xx - 0.5) + (yy - 0.5) * 3.0, 6.0) / 6.0
mask = np.full(c.shape, P['maskDark'], np.float32)
mask[..., 0][mx < 1/3] = P['maskLight']; mask[..., 1][(mx >= 1/3) & (mx < 2/3)] = P['maskLight']; mask[..., 2][mx >= 2/3] = P['maskLight']
c = c * mask
# rounded tube corners
r = P['corner']; cu = np.minimum(u, 1 - u); cv = np.minimum(v, 1 - v)
cdist = np.sqrt(np.maximum(r - cu, 0) ** 2 + np.maximum(r - cv, 0) ** 2)
c = c * np.clip((r - cdist) / 0.004, 0, 1)[..., None] * inside[..., None]
out = np.power(np.clip(c, 0, 1), 1 / 2.2)
Image.fromarray((out * 255).astype(np.uint8)).save(sys.argv[2])
