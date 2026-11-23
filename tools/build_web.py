"""Transforme web/app.html (le contenu de la page) en page complète pour l'appli Android."""
import os, pathlib, sys

root = pathlib.Path(__file__).resolve().parent.parent
src = (root / "web" / "app.html").read_text(encoding="utf-8")
repo = os.environ.get("GITHUB_REPOSITORY", "alexarzel/declic")

head = """<!doctype html><html lang="fr" data-declic-page><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>:root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}
body{margin:0;font:14px/1.5 system-ui,-apple-system,sans-serif}img{max-width:100%}[hidden]{display:none!important}</style>
</head><body>"""

page = head + src.replace("__REPO__", repo) + "</body></html>"
out = root / "android" / "app" / "src" / "main" / "assets" / "www" / "index.html"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(page, encoding="utf-8")
print(f"{out} ({len(page)} octets, dépôt {repo})", file=sys.stderr)
