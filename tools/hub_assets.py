#!/usr/bin/env python3
"""hub/menu.html -> app/src/main/assets/hub/menu.html for the APK.
The app has no INTERNET permission, so the Google Fonts <link> is replaced by local
@font-face rules; the woff2 files are fetched once into hub/fonts/ (kept for offline
rebuilds) and copied next to the page. Only latin + latin-ext subsets (Polish)."""
import os, re, shutil, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC, OUT = os.path.join(ROOT, "hub"), os.path.join(ROOT, "app/src/main/assets/hub")
FONTS = os.path.join(SRC, "fonts")
html = open(os.path.join(SRC, "menu.html"), encoding="utf-8").read()
link = re.search(r'<link rel="stylesheet" href="(https://fonts\.googleapis\.com/css2[^"]+)">', html)
css_path = os.path.join(FONTS, "fonts.css")
if not os.path.exists(css_path):
    os.makedirs(FONTS, exist_ok=True)
    ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    css = urllib.request.urlopen(urllib.request.Request(link.group(1).replace("&amp;", "&"), headers={"User-Agent": ua})).read().decode()
    blocks = re.findall(r"/\* ([\w-]+) \*/\s*(@font-face \{.*?\})", css, re.S)
    keep = []
    for subset, block in blocks:
        if subset not in ("latin", "latin-ext"): continue
        url = re.search(r"url\((https://[^)]+\.woff2)\)", block).group(1)
        name = re.sub(r"[^\w.]", "_", url.split("/s/")[1])
        urllib.request.urlretrieve(url, os.path.join(FONTS, name))
        keep.append(block.replace(url, "fonts/" + name))
    open(css_path, "w").write("\n".join(keep))
css = open(css_path).read()
html = html.replace('<link rel="preconnect" href="https://fonts.googleapis.com">\n', "")
html = html.replace(link.group(0), "<style>\n" + css + "\n</style>")
assert "googleapis" not in html
shutil.rmtree(OUT, ignore_errors=True); os.makedirs(os.path.join(OUT, "fonts"))
for f in os.listdir(FONTS):
    if f.endswith(".woff2"): shutil.copy(os.path.join(FONTS, f), os.path.join(OUT, "fonts", f))
open(os.path.join(OUT, "menu.html"), "w", encoding="utf-8").write(html)
# the built-in games' catalogue, when this build has one (not in the public repository)
if os.path.exists(os.path.join(SRC, "games.js")): shutil.copy(os.path.join(SRC, "games.js"), os.path.join(OUT, "games.js"))
print("menu.html", len(html) // 1024, "KB,", len(os.listdir(os.path.join(OUT, "fonts"))), "fonts")
