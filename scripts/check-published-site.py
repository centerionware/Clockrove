"""Check the static site is publishable as a cached Cloudflare site.

Cloudflare builds and deploys the site itself, so the repository only has to keep the
publishable directory self-contained: fails when a page exists but nothing links to
it, or when `_headers` names a file that is not published.
"""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

root = Path("website")
errors = []


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__()
        self.path = path
        self.references = []
        self.feed(path.read_text(encoding="utf-8"))
        self.close()

    def handle_starttag(self, tag, attributes):
        for name, value in attributes:
            if name in {"href", "src"} and value:
                self.references.append(value)


pages = {path.resolve(): Page(path) for path in root.rglob("*.html")}
for page in pages.values():
    resolved = []
    for reference in page.references:
        url = urlsplit(reference)
        if url.scheme or url.netloc or not unquote(url.path):
            continue
        requested = unquote(url.path)
        target = (root / requested.lstrip("/")) if requested.startswith("/") else (page.path.parent / requested)
        target = target.resolve()
        if target.is_dir():
            target /= "index.html"
        resolved.append(target)
    page.references = resolved

# Every published page must be reachable from the site index, so a page cannot be
# uploaded and still be invisible to a reader or to the Play Store listing.
index = (root / "index.html").resolve()
if index not in pages:
    errors.append("website/index.html is missing.")
else:
    seen, queue = {index}, [index]
    while queue:
        for target in pages[queue.pop()].references:
            if target in pages and target not in seen:
                seen.add(target)
                queue.append(target)
    for path in sorted(set(pages) - seen):
        errors.append(f"{path.relative_to(root.resolve())}: not linked from any page, so readers cannot reach it.")

headers = root / "_headers"
if not headers.is_file():
    errors.append("website/_headers is missing, so Pages would serve uncached pages with no security headers.")
else:
    pattern, rules, cache_for_index = None, {}, False
    for number, raw in enumerate(headers.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.split("#", 1)[0].rstrip() if raw.lstrip().startswith("#") else raw.rstrip()
        if not line.strip():
            continue
        if not line.startswith((" ", "\t")):
            pattern = line.strip()
            rules[pattern] = []
            continue
        if pattern is None:
            errors.append(f"website/_headers:{number}: header before any path pattern.")
            continue
        name, _, value = line.strip().partition(":")
        rules[pattern].append((name.strip(), value.strip()))
        if pattern == "/*" and name.strip().lower() == "cache-control":
            cache_for_index = True
        if not value.strip():
            errors.append(f"website/_headers:{number}: {name.strip()} has no value.")
    if not cache_for_index:
        errors.append('website/_headers: the "/*" rule must set Cache-Control for HTML pages.')
    for pattern in rules:
        if pattern in {"/*"} or any(character in pattern for character in "*?"):
            continue
        published = (root / pattern.lstrip("/")).resolve()
        if not published.is_file() and not published.is_dir():
            errors.append(f"website/_headers: rule for {pattern} matches nothing in the published site.")

if errors:
    raise SystemExit("\n".join(errors))
print(f"Checked publishing files for {len(pages)} pages.")
