#!/usr/bin/env python3
"""
NES Hub music & sound generator -> Standard MIDI files (original composition).

Channel layout follows the NES APU, and the hub's player maps it back to it:
  ch 0  pulse 1  lead        program = duty: 0 -> 12.5 %, 1 -> 25 %, 2 -> 50 %
  ch 1  pulse 2  harmony
  ch 2  triangle bass
  ch 9  noise    drums: 36 kick, 38 snare, 42 closed hat, 46 open hat
A marker "loopStart" tells the player where the loop begins. Pitch-bend range is set
to +/-24 semitones with RPN 0 so sound effects can sweep.

usage: gen_music.py OUTDIR      (deterministic: same script -> same files)
"""
import os, sys
import mido

TPB = 480                      # ticks per beat
OUT = sys.argv[1] if len(sys.argv) > 1 else "."

def song(bpm, tracks, markers=()):
    """tracks: {channel: [(start_beat, dur_beats, note, vel)] or extra events}"""
    mid = mido.MidiFile(type=1, ticks_per_beat=TPB)
    meta = mido.MidiTrack(); mid.tracks.append(meta)
    meta.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(bpm), time=0))
    meta.append(mido.MetaMessage("time_signature", numerator=4, denominator=4, time=0))
    t = 0
    for beat, name in sorted(markers):
        at = int(beat * TPB); meta.append(mido.MetaMessage("marker", text=name, time=at - t)); t = at
    for ch, spec in tracks.items():
        tr = mido.MidiTrack(); mid.tracks.append(tr)
        ev = []
        ev.append((0, 0, mido.Message("program_change", channel=ch, program=spec.get("program", 0))))
        for c, v in ((101, 0), (100, 0), (6, 24), (38, 0)):        # RPN 0: bend range 24 semitones
            ev.append((0, 0, mido.Message("control_change", channel=ch, control=c, value=v)))
        for start, dur, note, vel in spec["notes"]:
            a, b = int(start * TPB), int((start + dur) * TPB) - 1
            ev.append((a, 2, mido.Message("note_on", channel=ch, note=note, velocity=vel)))
            ev.append((b, 1, mido.Message("note_off", channel=ch, note=note, velocity=0)))
        for at, val in spec.get("bends", []):
            # bends are written 0..16383 (8192 = centre); mido wants -8192..8191
            ev.append((int(at * TPB), 1, mido.Message("pitchwheel", channel=ch, pitch=max(-8192, min(8191, val - 8192)))))
        ev.sort(key=lambda e: (e[0], e[1]))
        now = 0
        for at, _, msg in ev:
            tr.append(msg.copy(time=at - now)); now = at
    return mid

def save(mid, name):
    p = os.path.join(OUT, name); mid.save(p); return p

# ------------------------------------------------------------------ theme --
A, B = 0, 0
CHORDS = {   # root midi note (bass octave 2-3) and chord tones (pitch classes as midi in octave 4)
    "Am": (45, [69, 72, 76]), "F": (41, [65, 69, 72]), "C": (48, [67, 72, 76]), "G": (43, [67, 71, 74]),
    "E": (40, [68, 71, 76]), "Dm": (38, [65, 69, 74]),
}
SEC_A = ["Am", "F", "C", "G", "Am", "F", "G", "E"]
SEC_B = ["Dm", "Am", "F", "C", "Dm", "Am", "E", "E"]
# lead melody, one list per bar: (midi note or None, beats)
MEL_A = [
    [(76, 1), (81, 1), (79, .5), (76, .5), (72, .5), (74, .5)],
    [(72, 1.5), (69, .5), (72, 1), (77, 1)],
    [(76, 1), (79, 1), (76, .5), (72, .5), (67, 1)],
    [(71, 1.5), (74, .5), (79, 1), (74, 1)],
    [(76, 1), (81, 1), (79, .5), (76, .5), (72, .5), (74, .5)],
    [(72, .5), (74, .5), (77, 1), (81, 1), (77, 1)],
    [(79, .5), (77, .5), (76, .5), (74, .5), (71, 1), (74, 1)],
    [(76, 3), (None, 1)],
]
MEL_B = [
    [(74, .5), (77, .5), (81, 1), (84, 1), (81, 1)],
    [(81, 1.5), (79, .5), (76, 1), (72, 1)],
    [(77, .5), (76, .5), (77, .5), (81, .5), (84, 2)],
    [(84, .5), (83, .5), (81, .5), (79, .5), (76, 2)],
    [(77, 1), (74, 1), (77, 1), (81, 1)],
    [(76, 1), (72, 1), (76, 1), (81, 1)],
    [(80, 1), (83, 1), (80, 1), (76, 1)],
    [(76, .5), (80, .5), (83, .5), (88, .5), (83, 2)],
]
# A' = A with the last two bars lifted an octave and resolved on A
MEL_A2 = MEL_A[:6] + [
    [(79, .5), (81, .5), (83, .5), (86, .5), (83, 1), (86, 1)],
    [(81, 3), (None, 1)],
]

def theme():
    intro = 2
    form = [("A", SEC_A, MEL_A), ("B", SEC_B, MEL_B), ("A2", SEC_A, MEL_A2)]
    lead, harm, bass, drums = [], [], [], []
    # intro: bass + drums only, harmony fades in on bar 2
    bars = [("Am", None)] * intro
    for _, chords, mel in form:
        bars += list(zip(chords, mel))
    for i, (ch, mel) in enumerate(bars):
        t0 = i * 4
        root, tones = CHORDS[ch]
        # bass: root / octave eighths, walking up on the last beat to the next bar
        for k in range(8):
            n = root + (12 if k % 2 else 0)
            if k >= 6 and i + 1 < len(bars):
                nxt = CHORDS[bars[i + 1][0]][0]
                n = nxt - 2 if k == 6 else nxt - 1
            bass.append((t0 + k * .5, .45, n, 100))
        # harmony: up-down arpeggio in eighths, one octave below the lead
        if i >= 1:
            pat = [tones[0], tones[1], tones[2], tones[1] + 12 if False else tones[1], tones[0], tones[2], tones[1], tones[2]]
            for k, n in enumerate(pat):
                harm.append((t0 + k * .5, .4, n - 12 + 12, 52 if i >= intro else 34))
        # drums: kick 1 & 3, snare 2 & 4, closed hats on eighths, open hat + fill every 8th bar
        last_of_phrase = (i - intro) % 8 == 7 and i >= intro
        for k in range(8):
            drums.append((t0 + k * .5, .1, 46 if (k == 7 and last_of_phrase) else 42, 60))
        drums += [(t0, .2, 36, 110), (t0 + 2, .2, 36, 110)]
        if last_of_phrase:
            drums += [(t0 + 1, .2, 38, 100), (t0 + 3, .2, 38, 100), (t0 + 3.5, .2, 38, 90), (t0 + 3.75, .2, 38, 80)]
        else:
            drums += [(t0 + 1, .2, 38, 100), (t0 + 3, .2, 38, 100)]
        # lead
        if mel:
            t = t0
            for n, d in mel:
                if n is not None: lead.append((t, d * .92, n, 100))
                t += d
    return song(132, {
        0: {"program": 1, "notes": lead},     # 25 % pulse
        1: {"program": 0, "notes": harm},     # 12.5 % pulse
        2: {"notes": bass},
        9: {"notes": drums},
    }, markers=[(intro * 4, "loopStart")])

# ------------------------------------------------------------- jingle/sfx --
def boot_jingle():   # "power on" fanfare for the start screen, ~1.6 s
    up = [69, 72, 76, 81]
    lead = [(i * .25, .22, n, 100) for i, n in enumerate(up)] + [(1, 1.2, 88, 110)]
    harm = [(1, 1.2, 81, 70), (1, 1.2, 76, 60)]
    return song(150, {0: {"program": 2, "notes": lead}, 1: {"program": 1, "notes": harm},
                      2: {"notes": [(1, 1.2, 45, 100)]}, 9: {"notes": [(1, .3, 36, 110), (1, .3, 46, 70)]}})

def sfx():
    S = {}
    S["move"] = song(240, {0: {"program": 0, "notes": [(0, .2, 83, 90)]}})
    S["select"] = song(240, {0: {"program": 1, "notes": [(0, .25, 76, 100), (.25, .5, 83, 100)]}})
    S["back"] = song(240, {0: {"program": 1, "notes": [(0, .25, 83, 100), (.25, .5, 76, 100)]}})
    S["flap"] = song(240, {9: {"notes": [(0, .15, 42, 90)]}, 2: {"notes": [(0, .2, 52, 90)]}})
    # cassette going in: noise thump + triangle dropping an octave
    S["insert"] = song(240, {9: {"notes": [(0, .4, 36, 127), (0, .3, 38, 70)]},
                             2: {"notes": [(0, .6, 45, 110)], "bends": [(0, 8192), (.1, 6000), (.3, 4100), (.55, 2048)]}})
    # power on: pulse sweeping up two octaves (bend range +/-24 -> 8191 = +24 st)
    S["power"] = song(240, {0: {"program": 2, "notes": [(0, 1.4, 45, 110)],
                                "bends": [(0, 8192)] + [(k * .1, 8192 + int(8191 * k / 12)) for k in range(1, 13)]}})
    return S


# ============================================================ band version ==
# The same composition arranged for General MIDI instruments and rendered with a real
# sample bank (FluidSynth + GeneralUser GS) by render.sh. Channels / programs:
#   0 lead guitar (30 distortion) / brass (61) in B     1 strings pad (48)
#   2 fingered bass (33)                                3 rhythm guitar power chords (29)
#   4 square-lead arpeggio (80), the NES nod            5 octave doubling (48 strings / 56 trumpet)
#   9 drums (GM kit)
def song_gm(bpm, parts, markers=()):
    mid = mido.MidiFile(type=1, ticks_per_beat=TPB)
    meta = mido.MidiTrack(); mid.tracks.append(meta)
    meta.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(bpm), time=0))
    t = 0
    for beat, name in sorted(markers):
        at = int(beat * TPB); meta.append(mido.MetaMessage("marker", text=name, time=at - t)); t = at
    for ch, spec in parts.items():
        tr = mido.MidiTrack(); mid.tracks.append(tr); ev = []
        for c, v in ((7, spec.get("vol", 100)), (10, spec.get("pan", 64)), (91, spec.get("rev", 40)), (93, spec.get("cho", 0))):
            ev.append((0, 0, mido.Message("control_change", channel=ch, control=c, value=v)))
        for at, prog in spec.get("progs", [(0, spec.get("program", 0))]):
            ev.append((int(at * TPB), 0, mido.Message("program_change", channel=ch, program=prog)))
        for start, dur, note, vel in spec["notes"]:
            a, b = int(start * TPB), max(int(start * TPB) + 1, int((start + dur) * TPB) - 1)
            ev.append((a, 2, mido.Message("note_on", channel=ch, note=note, velocity=min(127, vel))))
            ev.append((b, 1, mido.Message("note_off", channel=ch, note=note, velocity=0)))
        for at, val in spec.get("bends", []):
            ev.append((int(at * TPB), 1, mido.Message("pitchwheel", channel=ch, pitch=max(-8192, min(8191, val - 8192)))))
        ev.sort(key=lambda e: (e[0], e[1])); now = 0
        for at, _, msg in ev: tr.append(msg.copy(time=at - now)); now = at
    return mid

def theme_band(body_repeats=2):
    """intro + body (A B A') x body_repeats; the player loops on the last body copy, so
    the reverb tail of one pass is already in the start of the next (seamless loop)."""
    intro = 2
    body = [(SEC_A, MEL_A, "A"), (SEC_B, MEL_B, "B"), (SEC_A, MEL_A2, "A2")]
    bars = [("Am", None, "I")] * intro
    for _ in range(body_repeats):
        for chords, mel, name in body: bars += [(c, m, name) for c, m in zip(chords, mel)]
    lead, pad, bass, rhy, arp, dbl, drums = [], [], [], [], [], [], []
    lead_progs, dbl_progs = [(0, 30)], [(0, 48)]
    for i, (ch, mel, sec) in enumerate(bars):
        t0 = i * 4; root, tones = CHORDS[ch]
        first_of_section = i == 0 or bars[i - 1][2] != sec
        if first_of_section and sec != "I":
            lead_progs.append((t0 - .01 if t0 else 0, 61 if sec == "B" else 30))
            dbl_progs.append((t0 - .01 if t0 else 0, 56 if sec == "B" else 48))
        # bass: eighths with octave jumps, approach note into the next bar
        for k in range(8):
            n = root + (12 if k in (3, 7) else 0)
            if k == 7 and i + 1 < len(bars): n = CHORDS[bars[i + 1][0]][0] - 1
            bass.append((t0 + k * .5, .42, n, 96 if k % 2 == 0 else 80))
        # rhythm guitar: palm-muted power chords (root + fifth + octave) in eighths
        if sec != "I" or i == intro - 1:
            r = root + 12
            for k in range(8):
                v = 92 if k in (0, 3, 6) else 64
                for n in (r, r + 7, r + 12): rhy.append((t0 + k * .5, .32, n, v))
        # strings pad: whole-bar chord, voiced around middle C
        if sec != "I":
            for n in tones: pad.append((t0, 3.9, n - 12, 58 if sec != "B" else 70))
        # NES nod: quiet square arpeggio in sixteenths during B and the intro
        if sec in ("B", "I"):
            seq = [tones[0], tones[1], tones[2], tones[1] + 12]
            for k in range(16): arp.append((t0 + k * .25, .2, seq[k % 4] + 12, 44))
        # drums
        hats = 42 if sec != "B" else 46
        for k in range(8): drums.append((t0 + k * .5, .1, hats if k % 2 else 42, 70 if k % 2 else 54))
        drums += [(t0, .2, 36, 112), (t0 + 1.5, .2, 36, 90), (t0 + 2, .2, 36, 108)]
        drums += [(t0 + 1, .2, 38, 104), (t0 + 3, .2, 38, 108)]
        if first_of_section and sec != "I": drums.append((t0, 1.5, 49, 100))
        last = (i + 1 == len(bars)) or bars[i + 1][2] != sec
        if last and sec != "I":   # tom fill into the next section
            drums += [(t0 + 3, .2, 50, 96), (t0 + 3.25, .2, 48, 96), (t0 + 3.5, .2, 47, 100), (t0 + 3.75, .2, 45, 104)]
        # lead (+ octave doubling in B and A')
        if mel:
            t = t0
            for n, d in mel:
                if n is not None:
                    lead.append((t, d * .95, n - 12 if sec != "B" else n, 104))
                    if sec in ("B", "A2"): dbl.append((t, d * .95, n, 70))
                t += d
    beats_body = sum(len(b[0]) for b in body) * 4
    loop_at = (intro + (body_repeats - 1) * len(body) * 8) * 4
    return song_gm(132, {
        0: {"progs": lead_progs, "notes": lead, "vol": 112, "pan": 58, "rev": 55, "cho": 20},
        1: {"program": 48, "notes": pad, "vol": 78, "pan": 70, "rev": 80},
        2: {"program": 33, "notes": bass, "vol": 108, "pan": 64, "rev": 20},
        3: {"program": 29, "notes": rhy, "vol": 70, "pan": 30, "rev": 30},
        4: {"program": 80, "notes": arp, "vol": 60, "pan": 98, "rev": 50},
        5: {"progs": dbl_progs, "notes": dbl, "vol": 74, "pan": 84, "rev": 70},
        9: {"notes": drums, "vol": 110, "rev": 30},
    }, markers=[(loop_at, "loopStart"), (loop_at + beats_body, "loopEnd")])

def band_sfx():
    S = {}
    S["move"] = song_gm(240, {0: {"program": 9, "notes": [(0, .3, 88, 90)], "rev": 30}})            # glockenspiel tick
    S["select"] = song_gm(240, {0: {"program": 8, "notes": [(0, .3, 84, 100), (.25, .8, 91, 104)], "rev": 50}})   # celesta up
    S["back"] = song_gm(240, {0: {"program": 8, "notes": [(0, .3, 91, 96), (.25, .8, 84, 96)], "rev": 50}})
    S["flap"] = song_gm(240, {9: {"notes": [(0, .2, 76, 110), (.12, .2, 77, 90)], "rev": 20}})       # wood blocks
    S["insert"] = song_gm(240, {9: {"notes": [(0, .3, 41, 120), (0, .3, 39, 80), (.05, .3, 35, 110)], "rev": 25},
                                1: {"program": 120, "notes": [(0, .4, 52, 100)], "rev": 10}})        # thump + fret noise
    S["power"] = song_gm(120, {0: {"program": 119, "notes": [(0, 1.6, 60, 110)], "rev": 40},          # reverse cymbal swell
                               1: {"program": 61, "notes": [(1.5, 1.5, 57, 110), (1.5, 1.5, 64, 104), (1.5, 1.5, 69, 104)], "rev": 60},
                               9: {"notes": [(1.5, 2, 49, 110), (1.5, .4, 36, 120)]}})
    S["boot"] = song_gm(132, {0: {"program": 61, "notes": [(0, .45, 57, 104), (.5, .45, 60, 104), (1, .45, 64, 108), (1.5, 2.5, 69, 118)], "rev": 60},
                              1: {"program": 48, "notes": [(1.5, 2.5, n, 80) for n in (57, 64, 69, 72)], "rev": 80},
                              2: {"program": 47, "notes": [(0, .3, 45, 110), (1.5, .6, 45, 120)], "rev": 40},
                              9: {"notes": [(1.5, 2, 49, 110), (1.5, .3, 36, 120)]}})
    return S


# ============================================================ calm NES menu ==
# A quiet menu loop in the style of NES title/file-select screens: slow, F major,
# seventh chords, long melody notes. Rendered as real 2A03-style chip audio by
# nes_render.py (pulse 12.5/25 %, stepped triangle, LFSR noise, melody echo).
CALM_A = ["Fmaj7", "Em7", "Dm7", "Cmaj7", "Bbmaj7", "Am7", "Gm7", "C7sus"]
CALM_B = ["Dm7", "Bbmaj7", "Am7", "Gm7", "Dm7", "Bbmaj7", "Gm7", "C7sus"]
CALM_CH = {   # bass root (oct 2) and chord tones (oct 4)
    "Fmaj7": (41, [65, 69, 72, 76]), "Em7": (40, [64, 67, 71, 74]), "Dm7": (38, [62, 65, 69, 72]),
    "Cmaj7": (36, [60, 64, 67, 71]), "Bbmaj7": (46, [58, 62, 65, 69]), "Am7": (45, [57, 60, 64, 67]),
    "Gm7": (43, [55, 58, 62, 65]), "C7sus": (36, [60, 65, 67, 70]),
}
CALM_MEL_A = [
    [(81, 2), (79, 1), (77, 1)], [(76, 3), (74, .5), (76, .5)], [(77, 2), (81, 1), (84, 1)], [(83, 3), (None, 1)],
    [(86, 2), (84, 1), (81, 1)], [(79, 2), (76, 1), (72, 1)], [(74, 1.5), (77, .5), (81, 1), (79, 1)], [(79, 1), (77, 1), (76, 2)],
]
CALM_MEL_B = [
    [(77, 1), (81, 1), (86, 2)], [(84, 1.5), (81, .5), (77, 2)], [(76, 2), (79, 1), (84, 1)], [(82, 3), (81, 1)],
    [(81, 2), (77, 1), (74, 1)], [(77, 2), (74, 1), (77, 1)], [(79, 2), (81, 1), (82, 1)], [(84, 3), (None, 1)],
]

def calm_theme(body_repeats=2):
    intro = 2
    body = [(CALM_A, CALM_MEL_A, "A"), (CALM_B, CALM_MEL_B, "B")]
    bars = [("Fmaj7", None, "I"), ("Fmaj7", None, "I")]
    for _ in range(body_repeats):
        for chords, mel, name in body: bars += [(c, m, name) for c, m in zip(chords, mel)]
    lead, arp, bass, noise = [], [], [], []
    for i, (ch, mel, sec) in enumerate(bars):
        t0 = i * 4; root, tones = CALM_CH[ch]
        # gentle up-and-down arpeggio in eighths
        seq = tones + [tones[2], tones[1]] + [tones[0] + 12, tones[3]]
        for k in range(8): arp.append((t0 + k * .5, .45, seq[k] , 40 if sec != "B" else 48))
        # triangle bass: root half note, fifth half note
        bass += [(t0, 1.9, root, 100), (t0 + 2, 1.9, root + 7, 92)]
        # soft shaker on the off-beats in B only
        if sec == "B":
            for k in range(8): noise.append((t0 + k * .5, .06, 42, 30 if k % 2 else 18))
        if mel:
            t = t0
            for n, d in mel:
                if n is not None: lead.append((t, d * .96, n, 96))
                t += d
    loop_at = (intro + (body_repeats - 1) * 16) * 4
    return song(84, {
        0: {"program": 1, "notes": lead},     # 25 % pulse melody (the renderer adds the echo)
        1: {"program": 0, "notes": arp},      # 12.5 % pulse arpeggio
        2: {"notes": bass},
        9: {"notes": noise},
    }, markers=[(loop_at, "loopStart"), (loop_at + 16 * 4, "loopEnd")])

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    files = [save(theme(), "theme.mid"), save(boot_jingle(), "boot.mid")]
    files += [save(m, f"sfx_{k}.mid") for k, m in sfx().items()]
    files += [save(calm_theme(), "calm_theme.mid")]
    files += [save(theme_band(), "band_theme.mid")] + [save(m, f"band_{k}.mid") for k, m in band_sfx().items()]
    for p in files:
        m = mido.MidiFile(p)
        notes = sum(1 for tr in m.tracks for e in tr if e.type == "note_on")
        print(f"{os.path.basename(p):16} {os.path.getsize(p):5} B  {m.length:6.2f} s  {notes:4} notes")
