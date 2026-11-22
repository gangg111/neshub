#!/usr/bin/env python3
"""Remove DT_RPATH / DT_RUNPATH from an ELF64 little-endian file, in place.

Termux's clang driver hard-codes "-rpath=/data/data/com.termux/files/usr/lib"
into every link.  Left in place, an .so inside an APK would ask the Android
loader to search Termux's lib directory first and could pick up Termux's libc
instead of the system one.  patchelf is not available on this device, so this
does the one edit it would have done: drop the entry from the PT_DYNAMIC array
and shift the rest up, leaving the terminating DT_NULL where the array ends.

Usage: strip_runpath.py <file> [...]
Exits non-zero if a file could not be parsed.  Removing nothing is not an error.
"""
import struct
import sys

DT_NULL = 0
DT_RPATH = 15
DT_RUNPATH = 29
PT_DYNAMIC = 2


def strip(path):
    with open(path, "rb") as f:
        blob = bytearray(f.read())

    if blob[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if blob[4] != 2 or blob[5] != 1:
        raise ValueError("only 64-bit little-endian ELF is supported")

    e_phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", blob, 0x36)

    removed = 0
    for i in range(e_phnum):
        ph = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", blob, ph)[0]
        if p_type != PT_DYNAMIC:
            continue
        p_offset, = struct.unpack_from("<Q", blob, ph + 0x08)
        p_filesz, = struct.unpack_from("<Q", blob, ph + 0x20)

        # Read the entries up to (and including) the first DT_NULL.
        entries = []
        for j in range(p_filesz // 16):
            tag, val = struct.unpack_from("<qQ", blob, p_offset + j * 16)
            entries.append((tag, val))
            if tag == DT_NULL:
                break

        kept = [e for e in entries if e[0] not in (DT_RPATH, DT_RUNPATH)]
        removed += len(entries) - len(kept)
        if len(kept) == len(entries):
            continue

        # Re-pack, padding back out with DT_NULL so nothing after the array
        # moves and the program header stays correct.
        while len(kept) < len(entries):
            kept.append((DT_NULL, 0))
        for j, (tag, val) in enumerate(kept):
            struct.pack_into("<qQ", blob, p_offset + j * 16, tag, val)

    if removed:
        with open(path, "wb") as f:
            f.write(blob)
    return removed


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    for path in argv[1:]:
        try:
            n = strip(path)
        except (OSError, ValueError) as exc:
            print("strip_runpath: %s: %s" % (path, exc), file=sys.stderr)
            return 1
        print("strip_runpath: %s: removed %d rpath entr%s"
              % (path, n, "y" if n == 1 else "ies"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
