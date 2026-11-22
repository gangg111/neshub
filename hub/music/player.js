/* ---------------------------------------------------------------- audio --
   Music and sounds are MIDI files made by hub/music/gen_music.py (original
   composition), embedded below and played by a small NES-style synthesiser:
   channel 0/1 pulse (program = duty 12.5/25/50 %), 2 triangle, 9 noise drums.
   A "loopStart" marker sets where the theme loops. Sound starts on the first tap. */
const MIDI = /*MIDI_DATA*/{};
let ac = null, master = null, musicBus = null, sfxBus = null, musicOn = true, music = null;
try { musicOn = localStorage.getItem("nesHubMusic") !== "0"; } catch (e) {}

function parseMidi(b64) {
  const d = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  let p = 0;
  const u32 = () => (d[p++] << 24 | d[p++] << 16 | d[p++] << 8 | d[p++]) >>> 0, u16 = () => d[p++] << 8 | d[p++];
  const vlq = () => { let v = 0, c; do { c = d[p++]; v = (v << 7) | (c & 127); } while (c & 128); return v; };
  p = 8; u16(); const ntr = u16(), tpb = u16();
  const raw = [];                                   // [tick, kind, ch, a, b]
  let tempo = 500000, loopTick = 0;
  for (let t = 0; t < ntr; t++) {
    p += 4; const end = u32() + p; let tick = 0, run = 0;
    while (p < end) {
      tick += vlq(); let st = d[p];
      if (st & 128) p++; else st = run;
      if (st === 0xFF) { const ty = d[p++], len = vlq(), s = p; p += len;
        if (ty === 0x51) tempo = d[s] << 16 | d[s + 1] << 8 | d[s + 2];
        if (ty === 0x06 && String.fromCharCode(...d.slice(s, s + len)) === "loopStart") loopTick = tick;
        continue; }
      if (st === 0xF0 || st === 0xF7) { p += vlq(); continue; }
      run = st; const ty = st >> 4, ch = st & 15, a = d[p++], b = (ty === 0xC || ty === 0xD) ? 0 : d[p++];
      raw.push([tick, ty === 0x9 && b > 0 ? "on" : (ty === 0x8 || ty === 0x9) ? "off" : ty === 0xC ? "prog" : ty === 0xE ? "bend" : "cc", ch, a, b]);
    }
  }
  const sec = tempo / 1e6 / tpb;
  raw.sort((x, y) => x[0] - y[0] || (x[1] === "off" ? -1 : 1));
  const lastTick = raw.length ? raw[raw.length - 1][0] : 0, bar = tpb * 4;
  return { ev: raw.map(e => ({ t: e[0] * sec, k: e[1], ch: e[2], a: e[3], b: e[4] })),
           loop: loopTick * sec, end: Math.ceil((lastTick + 1) / bar) * bar * sec };
}

const waves = {};
function pulseWave(ctx, duty) {
  const key = duty + "@" + ctx.sampleRate;
  if (waves[key] && waves[key].ctx === ctx) return waves[key].w;
  const n = 64, re = new Float32Array(n), im = new Float32Array(n);
  for (let k = 1; k < n; k++) { re[k] = (2 / (k * Math.PI)) * Math.sin(2 * k * Math.PI * duty); }
  const w = ctx.createPeriodicWave(re, im); waves[key] = { ctx, w }; return w;
}
let noiseBufs = new WeakMap();
function noiseBuf(ctx) {
  if (!noiseBufs.has(ctx)) { const b = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate), x = b.getChannelData(0);
    let r = 1; for (let i = 0; i < x.length; i++) { r = (r * 1103515245 + 12345) & 0x7fffffff; x[i] = (r / 0x3fffffff) - 1; } noiseBufs.set(ctx, b); }
  return noiseBufs.get(ctx);
}
const DUTY = [.125, .25, .5];
const DRUM = { 36: ["lowpass", 180, .16, 1.1], 38: ["bandpass", 1900, .13, .55], 42: ["highpass", 7000, .035, .25], 46: ["highpass", 6000, .22, .3] };
const N = n => 440 * Math.pow(2, (n - 69) / 12);

/* One player per song: schedules its events a little ahead of the clock, loops at
   "loopStart", and applies program (duty), pitch bend (+/-24 semitones) per channel. */
function MidiPlayer(ctx, dest, song, { loop = false, vol = 1 } = {}) {
  const prog = {}, bend = {}, live = {}; let i = 0, base = ctx.currentTime + .05, timer = null, stopped = false;
  const out = ctx.createGain(); out.gain.value = vol; out.connect(dest);
  function bendFactor(ch) { return Math.pow(2, (bend[ch] || 0) * 24 / 12); }
  function noteOn(e, t) {
    if (e.ch === 9) {
      const [type, f, dur, v] = DRUM[e.a] || DRUM[42];
      const s = ctx.createBufferSource(), fl = ctx.createBiquadFilter(), g = ctx.createGain();
      s.buffer = noiseBuf(ctx); fl.type = type; fl.frequency.value = f;
      g.gain.setValueAtTime(v * e.b / 127 * .5, t); g.gain.exponentialRampToValueAtTime(.001, t + dur);
      s.connect(fl); fl.connect(g); g.connect(out); s.start(t, Math.random() * .5); s.stop(t + dur + .02);
      return;
    }
    const o = ctx.createOscillator(), g = ctx.createGain();
    if (e.ch === 2) o.type = "triangle"; else o.setPeriodicWave(pulseWave(ctx, DUTY[prog[e.ch] || 0] || .5));
    o.frequency.setValueAtTime(N(e.a) * bendFactor(e.ch), t);
    const peak = (e.ch === 2 ? .34 : e.ch === 1 ? .1 : .16) * e.b / 127;
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(peak, t + .004);
    if (e.ch !== 2) g.gain.setTargetAtTime(peak * .65, t + .03, .08);   // pulse decay to sustain, triangle stays flat
    o.connect(g); g.connect(out); o.start(t);
    (live[e.ch] ||= {})[e.a] = { o, g, note: e.a };
  }
  function noteOff(e, t) {
    const v = live[e.ch] && live[e.ch][e.a]; if (!v) return;
    v.g.gain.cancelScheduledValues(t); v.g.gain.setTargetAtTime(0, t, .012); v.o.stop(t + .08);
    delete live[e.ch][e.a];
  }
  function apply(e, t) {
    if (e.k === "on") noteOn(e, t); else if (e.k === "off") noteOff(e, t);
    else if (e.k === "prog") prog[e.ch] = e.a;
    else if (e.k === "bend") { bend[e.ch] = ((e.b << 7 | e.a) - 8192) / 8192;
      for (const v of Object.values(live[e.ch] || {})) v.o.frequency.setValueAtTime(N(v.note) * bendFactor(e.ch), t); }
  }
  function pump() {
    if (stopped) return;
    const horizon = ctx.currentTime + .25;
    while (true) {
      if (i >= song.ev.length) { if (!loop) return; base += song.end - song.loop; i = song.ev.findIndex(e => e.t >= song.loop); if (i < 0) return; }
      const e = song.ev[i], t = base + e.t;
      if (t > horizon) break;
      apply(e, Math.max(t, ctx.currentTime)); i++;
    }
  }
  return {
    start() { pump(); if (loop || song.ev.length > 40) timer = setInterval(pump, 50); return this; },
    stop() { stopped = true; clearInterval(timer); const t = ctx.currentTime;
      out.gain.setTargetAtTime(0, t, .05); setTimeout(() => out.disconnect(), 400); },
    pumpAll() { while (i < song.ev.length) { const e = song.ev[i++]; apply(e, base + e.t); } },   // offline rendering
  };
}

const SONGS = {};
const song = name => SONGS[name] ||= parseMidi(MIDI[name]);
function audio() {
  if (ac) return ac;
  ac = new (window.AudioContext || window.webkitAudioContext)();
  master = ac.createGain(); master.gain.value = .9; master.connect(ac.destination);
  musicBus = ac.createGain(); musicBus.gain.value = .55; musicBus.connect(master);
  sfxBus = ac.createGain(); sfxBus.gain.value = .9; sfxBus.connect(master);
  return ac;
}
function startMusic() {
  if (!musicOn || music || !ac) return;
  music = MidiPlayer(ac, musicBus, song("theme"), { loop: true }).start();
}
function stopMusic() { if (music) { music.stop(); music = null; } }
function play(name) { if (ac) MidiPlayer(ac, sfxBus, song(name)).start(); }
const sfx = {
  move: () => play("sfx_move"), select: () => play("sfx_select"), back: () => play("sfx_back"),
  flap: () => play("sfx_flap"), insert: () => play("sfx_insert"), power: () => play("sfx_power"),
  boot: () => play("boot"),
};
// Used by tests: render [secs] of a song offline and report its level.
window.__renderMidi = async (name, secs) => {
  const ctx = new OfflineAudioContext(1, 48000 * secs, 48000);
  const p = MidiPlayer(ctx, ctx.destination, song(name), { loop: true, vol: .9 * .55 }); p.pumpAll();
  const buf = await ctx.startRendering(), x = buf.getChannelData(0);
  let peak = 0, sum = 0; for (const v of x) { peak = Math.max(peak, Math.abs(v)); sum += v * v; }
  return { peak, rms: Math.sqrt(sum / x.length) };
};
