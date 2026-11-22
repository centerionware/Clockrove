"""Generate the typed Flutter message keys and English fallback."""

import argparse
import json
import re
from pathlib import Path

from catalog_source import read_catalog

SOURCE = Path("localizations/en.yaml")
TARGET = Path("lib/l10n/app_messages.g.dart")


def generate():
    messages = read_catalog(SOURCE)
    lines = [
        "// Generated from localizations/en.yaml by tool/flutter_catalog.py.",
        "// dart format off",
        "part of 'app_localizations.dart';",
        "",
        "enum AppMessage {",
    ]
    for key, text in messages.items():
        if not re.fullmatch(r"[a-z][a-zA-Z0-9]*", key):
            raise ValueError(f"{SOURCE}: invalid Dart message key {key}")
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"{SOURCE}: {key} must have a nonempty string value")
        literal = json.dumps(text, ensure_ascii=False).replace("$", r"\$")
        lines.append(f"  {key}({literal}),")
    lines[-1] = lines[-1].removesuffix(",") + ";"
    lines += ["", "  const AppMessage(this.english);", "  final String english;", "}"]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = generate()
    if args.check:
        if not TARGET.is_file() or TARGET.read_text(encoding="utf-8") != expected:
            parser.error(f"outdated generated message source: {TARGET}")
    else:
        TARGET.write_text(expected, encoding="utf-8")


if __name__ == "__main__":
    main()
