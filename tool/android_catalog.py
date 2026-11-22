"""Generate Android string resources from the checked-in locale catalogs."""

import argparse
import re
from pathlib import Path
from xml.sax.saxutils import escape

from catalog_source import locale_names, read_catalog

CATALOGS = Path("localizations/android")
RESOURCES = Path("android/app/src/main/res")


def resource_xml(messages):
    rows = []
    for key, value in messages.items():
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{key}: expected nonempty copy")
        quoted = escape(value.replace("\\", "\\\\").replace('"', '\\"'))
        rows.append(f'    <string name="{key}">"{quoted}"</string>')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated from localizations/android by tool/android_catalog.py. -->\n'
        '<resources>\n' + "\n".join(rows) + "\n</resources>\n"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    english = read_catalog(CATALOGS / "en.yaml")
    tags = set(locale_names())
    actual_tags = {path.stem for path in CATALOGS.glob("*.yaml")}
    if tags != actual_tags:
        parser.error(f"Android catalog tags differ: {sorted(tags ^ actual_tags)}")
    stale = []
    for tag in sorted(tags):
        source = CATALOGS / f"{tag}.yaml"
        messages = read_catalog(source)
        if messages.keys() != english.keys():
            parser.error(f"{source}: catalog keys differ from English")
        for key, value in messages.items():
            arguments = r"%[1-9][0-9]*\$s"
            if sorted(re.findall(arguments, value)) != sorted(re.findall(arguments, english[key])):
                parser.error(f"{source}: format arguments differ for {key}")
        qualifier = f"b+{tag.replace('-', '+')}" if "-" in tag else tag
        directory = "values" if tag == "en" else f"values-{qualifier}"
        target = RESOURCES / directory / "widget_catalog.xml"
        xml = resource_xml(messages)
        if args.check:
            if not target.is_file() or target.read_text(encoding="utf-8") != xml:
                stale.append(str(target))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(xml, encoding="utf-8")
    if stale:
        parser.error(f"outdated generated resources: {', '.join(stale)}")


if __name__ == "__main__":
    main()
