// Folded-screen preview: NTSC (wide) frame -> CrtShader.kt (with bloom) -> TV bezel, as EmulatorView draws it.
const fs = require('fs');
const CanvasKitInit = require('canvaskit-wasm/bin/full/canvaskit.js');
(async () => {
  const CK = await CanvasKitInit({ locateFile: f => __dirname + '/node_modules/canvaskit-wasm/bin/full/' + f });
  const src = fs.readFileSync(process.argv[2], 'utf8').split('"""')[1];
  let err = ''; const eff = CK.RuntimeEffect.Make(src, e => { err += e; });
  if (!eff) { console.log('COMPILE FAILED:\n' + err); process.exit(1); }
  console.log('compiled OK, uniforms', eff.getUniformCount());
  const img = CK.MakeImageFromEncoded(fs.readFileSync(process.argv[3]));
  const bez = CK.MakeImageFromEncoded(fs.readFileSync(process.argv[4]));
  const SW = 2520, SH = 1080, W = Math.round(SH * 240 * 8 / 7 / 224), L = Math.round((SW - W) / 2);
  const surf = CK.MakeSurface(SW, SH); const c = surf.getCanvas(); c.clear(CK.BLACK);
  const ww = img.width(), cropL = Math.floor(8 * ww / 256), cropR = Math.floor(248 * ww / 256);
  const sh = eff.makeShaderWithChildren([cropL, 0, cropR - cropL, 224, L, 0, W, SH, 1, 1],
    [img.makeShaderOptions(CK.TileMode.Clamp, CK.TileMode.Clamp, CK.FilterMode.Nearest, CK.MipmapMode.None)]);
  const p = new CK.Paint(); p.setShader(sh); c.drawRect(CK.LTRBRect(L, 0, L + W, SH), p);
  // tv.png: 1914x822, opening x 428..1485, y 6..814 (as EmulatorView)
  // tv.png: 2552x1096, opening x 570..1980, y 8..1085 (as EmulatorView)
  const s = SH / 1078, bw = bez.width() * s, bh = bez.height() * s, bl = L + W / 2 - 1275 * s, bt = SH / 2 - 546.5 * s;
  const bp = new CK.Paint();
  c.drawImageRectOptions(bez, CK.LTRBRect(0, 0, bez.width(), bez.height()), CK.LTRBRect(bl, bt, bl + bw, bt + bh), CK.FilterMode.Linear, CK.MipmapMode.None, bp);
  if (bl > 0) c.drawImageRectOptions(bez, CK.LTRBRect(0, 0, 8, bez.height()), CK.LTRBRect(0, bt, bl + 1, bt + bh), CK.FilterMode.Linear, CK.MipmapMode.None, bp);
  if (bl + bw < SW) c.drawImageRectOptions(bez, CK.LTRBRect(bez.width() - 8, 0, bez.width(), bez.height()), CK.LTRBRect(bl + bw - 1, bt, SW, bt + bh), CK.FilterMode.Linear, CK.MipmapMode.None, bp);
  surf.flush(); fs.writeFileSync(process.argv[5], surf.makeImageSnapshot().encodeToBytes());
})();
