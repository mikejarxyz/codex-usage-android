"""Bundle the public privacy policy as restricted, offline HTML."""
import argparse
import html
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
text = html.escape((root / "PRIVACY.md").read_text(encoding="utf-8"))
text = re.sub(r"\[([^\]]+)\]\((https://[^)]+)\)", r'<a href="\2">\1</a>', text)
paragraphs = []
for paragraph in text.split("\n\n"):
    if paragraph.startswith("# "):
        paragraphs.append("<h1>" + paragraph[2:] + "</h1>")
    elif paragraph.startswith("## "):
        paragraphs.append("<h2>" + paragraph[3:] + "</h2>")
    else:
        paragraphs.append("<p>" + paragraph.replace("\n", " ") + "</p>")
document = ('<!doctype html><html lang="en"><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1"><title>Privacy</title>'
            '<style>body{background:#0f0f0f;color:#f1f1f1;font:16px sans-serif;line-height:1.5;margin:24px}'
            'h1{font-size:26px}h2{font-size:19px;margin-top:28px}a{color:#bdbdbd}</style>'
            + "".join(paragraphs) + "</html>\n")
assets = [root / "app/src/main/assets/privacy.html", root / "docs/privacy/index.html"]
for asset in assets:
    if args.check:
        if not asset.is_file() or asset.read_text(encoding="utf-8") != document:
            raise SystemExit("Privacy copy is stale; run python scripts/generate-privacy.py")
    else:
        asset.parent.mkdir(parents=True, exist_ok=True)
        asset.write_text(document, encoding="utf-8", newline="\n")
if args.check:
    print("In-app and hosted privacy copies match PRIVACY.md")
