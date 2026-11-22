"""Check that links and images in the static Clockrove site resolve."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__()
        self.ids = set()
        self.references = []
        self.feed(path.read_text(encoding="utf-8"))
        self.close()

    def handle_starttag(self, tag, attributes):
        for name, value in attributes:
            if name == "id" and value:
                if value in self.ids:
                    raise ValueError(f"Duplicate ID: {value}")
                self.ids.add(value)
            if name in {"href", "src"} and value:
                self.references.append(value)


root = Path("website").resolve()
pages = {path: Page(path) for path in root.rglob("*.html")}
if root / "index.html" not in pages:
    raise SystemExit("The documentation site is missing its index.html.")

errors = []
for path, page in pages.items():
    for reference in page.references:
        url = urlsplit(reference)
        if url.scheme or url.netloc:
            continue
        requested = unquote(url.path)
        if not requested:
            target = path
        elif requested.startswith("/"):
            target = root / requested.lstrip("/")
        else:
            target = path.parent / requested
        target = target.resolve()
        if target.is_dir():
            target /= "index.html"
        if not target.is_relative_to(root) or not target.is_file():
            errors.append(f"{path.relative_to(root)}: missing {reference}")
        elif url.fragment and target in pages:
            if unquote(url.fragment) not in pages[target].ids:
                errors.append(f"{path.relative_to(root)}: missing {reference}")

if errors:
    raise SystemExit("\n".join(errors))
print(f"Checked local links and assets in {len(pages)} documentation pages.")
