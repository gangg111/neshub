#!/usr/bin/env python3
"""
Calm menu theme -> 2A03-style chip audio, and menu sound effects -> layered foley.
  music: calm_theme.mid (gen_music.py), band-limited pulse (PolyBLEP, duty per program),
         NES 32-step triangle, 15-bit LFSR noise, delayed vibrato, melody echo,
         NES output filters (HP 90 Hz, LP 14 kHz). Loop points from the markers.
  sfx:   synthesised physical sounds (clicks, plastic body resonance, thumps, friction,
         transformer hum, CRT static) through a small room reverb.
usage: nes_render.py      (in hub/music; writes theme/boot/move/select/back/flap/insert/power.mp3)
"""
import json, os, subprocess, wave
import numpy as np, mido

SR = 44100
rng = np.random.default_rng(7)          # deterministic

# ----------------------------------------------------------------- helpers --
def fft_filter(x, lo=None, hi=None, order=2):
    """zero-phase Butterworth-shaped high/low pass in the frequency domain"""
    n = len(x); X = np.fft.rfft(x, n * 2); f = np.fft.rfftfreq(n * 2, 1 / SR)
    g = np.ones_like(f)
    if lo: g *= 1 / np.sqrt(1 + (lo / np.maximum(f, 1e-3)) ** (2 * order))
    if hi: g *= 1 / np.sqrt(1 + (f / hi) ** (2 * order))
    return np.fft.irfft(X * g, n * 2)[:n]

def bandpass(x, fc, q):
    n = len(x); X = np.fft.rfft(x, n * 2); f = np.fft.rfftfreq(n * 2, 1 / SR)
    g = 1 / (1 + (q * (f / fc - fc / np.maximum(f, 1e-3))) ** 2)
    return np.fft.irfft(X * g, n * 2)[:n]

def polyblep(t, dt):
    y = np.zeros_like(t)
    a = t < dt; x = t[a] / dt[a]; y[a] = 2 * x - x * x - 1
    b = t > 1 - dt; x = (t[b] - 1) / dt[b]; y[b] = x * x + 2 * x + 1
    return y

def pulse(freq, duty):
    """freq: per-sample Hz array -> band-limited pulse in -1..1"""
    dt = freq / SR; ph = np.cumsum(dt) % 1.0
    y = np.where(ph < duty, 1.0, -1.0)
    y += polyblep(ph, dt) - polyblep((ph - duty) % 1.0, dt)
    return y - (2 * duty - 1)              # remove DC like the NES output stage

TRI = np.concatenate([np.arange(15, -1, -1), np.arange(0, 16)]) / 7.5 - 1.0   # 32-step NES triangle
def triangle(freq):
    ph = np.cumsum(freq / SR) % 1.0
    return TRI[(ph * 32).astype(int) % 32]

def lfsr(n_bits=15, tap=1):
    r, out = 1, np.empty(32767)
    for i in range(32767):
        b = (r ^ (r >> tap)) & 1; r = (r >> 1) | (b << (n_bits - 1)); out[i] = 1.0 if r & 1 else -1.0
    return out
LFSR = lfsr()

def env_adsr(n, a=.005, d=.15, s=.7, rel=.06, note_len=None):
    t = np.arange(n) / SR
    e = np.minimum(t / a, 1.0) * (s + (1 - s) * np.exp(-np.maximum(t - a, 0) / d))
    if note_len is not None:
        off = t > note_len
        e[off] *= np.exp(-(t[off] - note_len) / (rel / 4))
    return e

def save_wav(path, x):
    x = np.atleast_2d(x)
    if x.shape[0] == 1: x = np.vstack([x, x])
    pcm = (np.clip(x.T, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())

def normalize(x, db=-1.0):
    return x * (10 ** (db / 20) / max(1e-9, np.max(np.abs(x))))

def to_mp3(x, name, rate="96k"):
    wav = f".{name}.wav"; save_wav(wav, x)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-codec:a", "libmp3lame", "-b:a", rate, f"{name}.mp3"], check=True)
    os.remove(wav); print(f"{name}.mp3".ljust(12), os.path.getsize(f"{name}.mp3"), "B", f"{x.shape[-1] / SR:.2f} s")

# ------------------------------------------------------------------- music --
def read_midi(path):
    m = mido.MidiFile(path); t = 0.0; tempo = 500000; on = {}; notes = []; prog = {}; marks = {}
    for e in mido.merge_tracks(m.tracks):
        t += mido.tick2second(e.time, m.ticks_per_beat, tempo)
        if e.type == "set_tempo": tempo = e.tempo
        elif e.type == "marker": marks[e.text] = t
        elif e.type == "program_change": prog[e.channel] = e.program
        elif e.type == "note_on" and e.velocity: on[(e.channel, e.note)] = (t, e.velocity)
        elif e.type in ("note_off", "note_on") and (e.channel, e.note) in on:
            s, v = on.pop((e.channel, e.note)); notes.append((e.channel, e.note, s, t - s, v))
    return notes, prog, marks, t

def render_theme(path):
    notes, prog, marks, length = read_midi(path)
    end = marks["loopEnd"]; total = int((end + 2.0) * SR)
    L, R = np.zeros(total), np.zeros(total)
    ML, MR = np.zeros(total), np.zeros(total)      # melody + echo, filtered separately (softer top)
    def add(sig, at, gl, gr, mel=False):
        l, r = (ML, MR) if mel else (L, R)
        i = int(at * SR); j = min(total, i + len(sig)); l[i:j] += sig[:j - i] * gl; r[i:j] += sig[:j - i] * gr
    for ch, n, s, d, v in notes:
        f0 = 440 * 2 ** ((n - 69) / 12); vel = v / 127
        if ch == 0:                                   # lead, 25 % pulse, delayed vibrato, echo
            k = int((d + .25) * SR); t = np.arange(k) / SR
            vib = 1 + (2 ** (.18 / 12) - 1) * np.sin(2 * np.pi * 5.2 * t) * np.clip((t - .28) / .3, 0, 1)
            sig = pulse(f0 * vib, .25) * env_adsr(k, .006, .35, .62, .08, d) * vel
            add(sig, s, .14, .14, True)
            echo = 60 / 84 * .75                       # dotted eighth
            add(pulse(f0 * vib, .125) * env_adsr(k, .006, .35, .62, .08, d) * vel, s + echo, .025, .06, True)
        elif ch == 1:                                 # arpeggio, 12.5 % pulse, plucked
            k = int((d + .1) * SR)
            sig = pulse(np.full(k, f0), .125) * env_adsr(k, .002, .09, .25, .05, d) * vel
            add(sig, s, .17, .09)
        elif ch == 2:                                 # triangle bass, constant volume (NES)
            k = int((d + .01) * SR); e = np.ones(k); e[-int(.01 * SR):] = np.linspace(1, 0, int(.01 * SR))
            e[:int(.003 * SR)] = np.linspace(0, 1, int(.003 * SR))
            add(triangle(np.full(k, f0)) * e * .42, s, .5, .5)
        elif ch == 9:                                 # soft shaker: short-mode LFSR, high clock
            k = int(.07 * SR); idx = (np.arange(k) * (110000 / SR)).astype(int) % len(LFSR)
            add(LFSR[idx] * np.exp(-np.arange(k) / SR / .018) * vel * .22, s, .4, .5)
    L += fft_filter(ML, hi=3200, order=2); R += fft_filter(MR, hi=3200, order=2)
    out = np.vstack([fft_filter(L, lo=90, hi=14000, order=1), fft_filter(R, lo=90, hi=14000, order=1)])
    out = normalize(out, -1.5)[:, :int((end + .3) * SR)]
    return out, {"loopStart": round(marks["loopStart"], 4), "loopEnd": round(end, 4)}

# --------------------------------------------------------------------- sfx --
def room(x, size=.35, wet=.18, damp=6000):
    """small-room reverb: decaying filtered noise impulse, decorrelated per side"""
    n = int(size * SR); t = np.arange(n) / SR
    out = []
    for side in range(2):
        ir = rng.standard_normal(n) * np.exp(-t / (size / 6.9) * 1.0)
        ir = fft_filter(ir, lo=150, hi=damp); ir /= np.sqrt(np.sum(ir ** 2))
        N = len(x) + n; y = np.fft.irfft(np.fft.rfft(x, N) * np.fft.rfft(ir, N), N)
        dry = np.concatenate([x, np.zeros(n)])
        out.append(dry * (1 - wet) + y * wet)
    return np.vstack(out)

def seg(sec): return np.zeros(int(sec * SR))
def put(buf, at, sig, g=1.0):
    i = int(at * SR); j = min(len(buf), i + len(sig)); buf[i:j] += sig[:j - i] * g
def click(ms=2.5, fc=4000, q=1.5):
    k = int(ms / 1000 * SR * 6); x = rng.standard_normal(k) * np.exp(-np.arange(k) / (ms / 1000 * SR))
    return bandpass(x, fc, q)
def modes(freqs, taus, amps, sec):
    t = np.arange(int(sec * SR)) / SR
    return sum(a * np.sin(2 * np.pi * f * t + rng.uniform(0, 6)) * np.exp(-t / tau) for f, tau, a in zip(freqs, taus, amps))
def thump(f=90, tau=.05, sec=.25, drop=.6):
    t = np.arange(int(sec * SR)) / SR
    ph = 2 * np.pi * np.cumsum(f * (drop + (1 - drop) * np.exp(-t / .02))) / SR
    return np.sin(ph) * np.exp(-t / tau) * np.minimum(t / .0015, 1)

def sfx_move():            # cursor: light plastic tick of a d-pad, small body ring
    b = seg(.25); put(b, 0, click(1.6, 5200, 2), .9); put(b, 0, modes([1850, 3320], [.012, .007], [.25, .12], .08))
    put(b, .002, thump(220, .012, .05), .35)
    return room(b, .25, .12)

def sfx_select():          # button press and release: clack, thump through the case, release tick
    b = seg(.45); put(b, 0, click(2.2, 3400, 1.4), 1.0); put(b, 0, thump(130, .035, .2), .8)
    put(b, 0, modes([980, 2240, 3900], [.03, .016, .008], [.3, .18, .08], .15))
    put(b, .085, click(1.4, 5600, 2), .45); put(b, .085, modes([2100], [.01], [.12], .05))
    return room(b, .35, .2)

def sfx_back():            # softer, duller press + short air whoosh downwards
    b = seg(.5); put(b, 0, click(2.4, 2200, 1.2), .8); put(b, 0, thump(95, .04, .2), .7)
    k = int(.22 * SR); t = np.arange(k) / SR; w = rng.standard_normal(k) * np.sin(np.pi * t / .22) ** 2
    put(b, .01, bandpass(w, 900, .8), .12)
    return room(b, .35, .2)

def sfx_flap():            # spring flaps folding inward: double plastic snap + spring creak
    b = seg(.5)
    for at, g in ((0, .9), (.028, .7)):
        put(b, at, click(1.8, 3000, 1.3), g); put(b, at, modes([760, 1630, 2900], [.04, .02, .01], [.22, .12, .06], .15), g)
    k = int(.16 * SR); t = np.arange(k) / SR
    creak = np.sin(2 * np.pi * np.cumsum(np.full(k, 420) + 60 * np.sin(2 * np.pi * 18 * t)) / SR)
    creak *= (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 55 * t))) * np.exp(-t / .06)
    put(b, .01, bandpass(creak, 1200, 2), .1)
    return room(b, .3, .18)

def sfx_insert():          # cart sliding in: friction, edge-connector scrape, heavy clunk, latch
    b = seg(1.0); k = int(.22 * SR); t = np.arange(k) / SR
    fr = rng.standard_normal(k) * np.sin(np.pi * t / .22) ** 1.5
    put(b, 0, bandpass(fr, 700, .7), .22); put(b, 0, bandpass(fr * (rng.random(k) < .05), 4500, 1.5), .4)
    put(b, .21, thump(70, .09, .5, .5), 1.0); put(b, .21, click(3.5, 1500, .9), .8)
    put(b, .21, modes([310, 640, 1180], [.09, .05, .03], [.3, .18, .1], .4))
    put(b, .3, click(1.5, 4800, 2), .5); put(b, .3, modes([2600], [.015], [.12], .06))
    return room(b, .45, .25, 5000)

def sfx_power():           # power switch: clack, transformer hum swelling, CRT degauss thoom + static
    b = seg(2.6); put(b, 0, click(2.5, 2600, 1.2), 1.0); put(b, 0, thump(110, .05, .3), .8)
    put(b, 0, modes([820, 1900], [.04, .02], [.25, .12], .2))
    t = np.arange(int(2.4 * SR)) / SR
    hum = sum(np.sin(2 * np.pi * 50 * h * t) / h for h in (1, 2, 3, 5)) * np.clip(t / .5, 0, 1) * np.exp(-np.maximum(t - .7, 0) / .6)
    put(b, .05, hum, .16)
    k = int(1.3 * SR); tt = np.arange(k) / SR
    thoom = np.sin(2 * np.pi * np.cumsum(60 + 35 * np.exp(-tt / .3)) / SR) * (1 - np.exp(-tt / .04)) * np.exp(-tt / .45)
    put(b, .12, thoom, .55)
    st = rng.standard_normal(k) * (rng.random(k) < .02) * np.exp(-tt / .35)
    put(b, .14, fft_filter(st, lo=1500, hi=9000), .5)
    whine = np.sin(2 * np.pi * 15734 * tt) * np.clip(tt / .3, 0, 1) * np.exp(-tt / .9)
    put(b, .14, whine, .02)
    return room(b, .5, .22, 5000)

def sfx_boot():            # TV coming on at the start screen: relay click, CRT thoom, static crackle
    b = seg(1.6); put(b, 0, click(1.8, 3800, 1.5), .7); put(b, 0, modes([1400, 2800], [.02, .01], [.2, .1], .1))
    k = int(1.1 * SR); tt = np.arange(k) / SR
    put(b, .06, np.sin(2 * np.pi * np.cumsum(55 + 30 * np.exp(-tt / .25)) / SR) * (1 - np.exp(-tt / .03)) * np.exp(-tt / .35), .6)
    st = rng.standard_normal(k) * (rng.random(k) < .04) * np.exp(-tt / .25)
    put(b, .07, fft_filter(st, lo=1200, hi=10000), .55)
    put(b, .07, fft_filter(rng.standard_normal(k), lo=2000, hi=7000) * np.exp(-tt / .12), .05)
    return room(b, .45, .2, 6000)

def trim(x, thresh=1e-3):
    a = np.max(np.abs(x), axis=0); last = np.nonzero(a > thresh)[0]
    return x[:, : (last[-1] + int(.02 * SR)) if len(last) else x.shape[1]]

if __name__ == "__main__":
    theme, loops = render_theme("calm_theme.mid")
    to_mp3(theme, "theme", "128k")
    # peaks in dBFS: small UI ticks quieter, the physical cartridge/power events loudest
    for name, fn, db in [("boot", sfx_boot, -4), ("move", sfx_move, -10), ("select", sfx_select, -6), ("back", sfx_back, -7),
                         ("flap", sfx_flap, -8), ("insert", sfx_insert, -2), ("power", sfx_power, -2)]:
        to_mp3(normalize(trim(fn()), db), name)
    json.dump({"theme": loops}, open("loops.json", "w"), indent=1); print(loops)
