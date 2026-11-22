#!/usr/bin/env python3
"""Render band_*.mid with FluidSynth + GeneralUser GS to MP3, and write loop points.
usage: render.py   (run in hub/music after gen_music.py)"""
import glob, json, os, subprocess, mido
SF = os.path.expanduser("~/.local/share/soundfonts/GeneralUser-GS.sf2")
loops = {}
for mid in sorted(glob.glob("band_*.mid")):
    name = mid[5:-4]
    m = mido.MidiFile(mid)
    # pad the end so reverb tails are rendered, not cut (FluidSynth stops at the last event)
    pad = mido.MidiTrack(); pad.append(mido.MetaMessage("text", text="tail", time=int(m.length * 0 + m.ticks_per_beat * 2 if name != "theme" else 1)))
    total = max(sum(e.time for e in tr) for tr in m.tracks)
    pad = mido.MidiTrack(); pad.append(mido.MetaMessage("text", text="tail", time=total + (m.ticks_per_beat * 3 if name != "theme" else 1)))
    m.tracks.append(pad); tmp = f".pad_{name}.mid"; m.save(tmp)
    wav = f".{name}.wav"
    subprocess.run(["fluidsynth", "-ni", "-q", "-g", "0.55", "-r", "44100", "-F", wav, SF, tmp], check=True, capture_output=True)
    rate = "128k" if name == "theme" else "96k"
    out = f"{name}.mp3"
    # loudness: every file peaks at -1 dBFS; length: the theme ends at its loop end,
    # sound effects at their last note plus a short reverb tail
    det = subprocess.run(["ffmpeg", "-hide_banner", "-i", wav, "-af", "volumedetect", "-f", "null", "-"], capture_output=True, text=True).stderr
    peak = float(det.split("max_volume:")[1].split("dB")[0])
    tempo0 = next((e.tempo for e in m.tracks[0] if e.type == "set_tempo"), 500000)
    marks0 = {}; tt = 0
    for e in m.tracks[0]:
        tt += e.time
        if e.type == "marker": marks0[e.text] = mido.tick2second(tt, m.ticks_per_beat, tempo0)
    length = marks0["loopEnd"] + .3 if "loopEnd" in marks0 else mido.MidiFile(mid).length + 1.0
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-t", f"{length:.3f}",
                    "-af", f"volume={-1 - peak:.2f}dB,afade=t=out:st={max(0, length - .25):.3f}:d=0.25" if name != "theme" else f"volume={-1 - peak:.2f}dB",
                    "-codec:a", "libmp3lame", "-b:a", rate, out], check=True)
    os.remove(tmp); os.remove(wav)
    # loop points from the markers (seconds)
    tempo, t, marks = 500000, 0, {}
    for e in mido.merge_tracks(mido.MidiFile(mid).tracks):
        t += mido.tick2second(e.time, m.ticks_per_beat, tempo)
        if e.type == "set_tempo": tempo = e.tempo
        if e.type == "marker": marks[e.text] = round(t, 4)
    if marks: loops[name] = marks
    print(f"{out:12} {os.path.getsize(out):8} B")
json.dump(loops, open("loops.json", "w"), indent=1); print("loops", loops)
