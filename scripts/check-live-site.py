"""Check the deployed Clockrove site serves the help page and the legal pages.

Deployment runs on Cloudflare, not in GitHub Actions. Run this after a Cloudflare
build finishes, from any machine:

    python3 scripts/check-live-site.py https://clockrove.pages.dev

It only reads public URLs; it sends no credentials.
"""
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

PAGES = {
    "/": "Clockrove Help",
    "/legal/": "Legal",
    "/legal/privacy.html": "Privacy Policy",
    "/legal/data-use.html": "Data Use Policy",
    "/legal/terms.html": "Terms of Use",
    "/legal/license.html": "Source",
}


def fetch(url, path):
    request = Request(url + path, headers={"User-Agent": "clockrove-site-check"})
    with urlopen(request, timeout=20) as response:
        return response.status, response.headers, response.read().decode("utf-8", "replace")


base = (sys.argv[1] if len(sys.argv) > 1 else "").rstrip("/")
if not base.startswith("https://"):
    raise SystemExit("Pass the deployed site URL, for example https://clockrove.pages.dev")

errors = []
for path, expected in PAGES.items():
    try:
        status, headers, body = fetch(base, path)
    except (HTTPError, URLError, TimeoutError) as error:
        errors.append(f"{path}: {error}")
        continue
    if status != 200:
        errors.append(f"{path}: status {status}")
        continue
    if expected not in body:
        errors.append(f"{path}: does not contain {expected!r}")
        continue
    print(f"{path}: {status}, cache-control {headers.get('Cache-Control', '(none)')}")

if errors:
    raise SystemExit(
        "\n".join(errors)
        + "\nThe Cloudflare build must publish the website directory itself, so that legal/ is uploaded with it."
    )
print(f"All {len(PAGES)} pages are served from {base}.")
