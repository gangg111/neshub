// Compiles the hub's AGSL CRT shader with Skia (CanvasKit, same SkSL compiler as Android's
// RuntimeShader) and renders a game frame through it.
const fs = require('fs');
const CanvasKitInit = require('canvaskit-wasm/bin/full/canvaskit.js');
(async () => {
  const CK = await CanvasKitInit({ locateFile: f => __dirname + '/node_modules/canvaskit-wasm/bin/full/' + f });
  const kt = fs.readFileSync(process.argv[2], 'utf8');
  const src = kt.split('"""')[1];
  let err = '';
  const eff = CK.RuntimeEffect.Make(src, e => { err += e; });
  if (!eff) { console.log('COMPILE FAILED:\n' + err); process.exit(1); }
  console.log('compiled OK, uniforms:', eff.getUniformCount(), 'children:', eff.getChildCount ? eff.getChildCount() : '?');
  const img = CK.MakeImageFromEncoded(fs.readFileSync(process.argv[3]));
  const W = +process.argv[5], H = +process.argv[6];
  const surf = CK.MakeSurface(W, H); const c = surf.getCanvas();
  const imgShader = img.makeShaderOptions(CK.TileMode.Clamp, CK.TileMode.Clamp, CK.FilterMode.Nearest, CK.MipmapMode.None);
  const crop = 8;
  const sh = eff.makeShaderWithChildren([crop, 0, img.width() - 2 * crop, img.height(), 0, 0, W, H, +(process.argv[7] ?? 1)], [imgShader]);
  const p = new CK.Paint(); p.setShader(sh);
  const t0 = Date.now(); c.drawRect(CK.LTRBRect(0, 0, W, H), p); surf.flush();
  console.log('rendered in', Date.now() - t0, 'ms (CPU)');
  fs.writeFileSync(process.argv[4], surf.makeImageSnapshot().encodeToBytes());
})();
