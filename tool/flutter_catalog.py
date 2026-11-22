"""Generate the typed Flutter message keys and English fallback."""

import argparse
import json
import re
from pathlib import Path

from catalog_source import LANGUAGE_TAG, locale_names, read_catalog

SOURCE = Path("localizations/en.yaml")
MESSAGES_TARGET = Path("lib/l10n/app_messages.g.dart")
LOCALES_TARGET = Path("lib/l10n/app_languages.g.dart")


def generate_messages():
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


def generate_locales():
    names = locale_names()
    catalogs = {path.stem for path in SOURCE.parent.glob("*.yaml")}
    if catalogs != names.keys():
        raise ValueError(f"Flutter catalog tags differ: {sorted(catalogs ^ names.keys())}")
    lines = [
        "// Generated from localizations/metadata/languages.yaml.",
        "// dart format off",
        "part of 'app_localizations.dart';",
        "",
        "const appLanguages = {",
    ]
    for tag, name in names.items():
        language, script, region = LANGUAGE_TAG.fullmatch(tag).groups()
        subtags = [f"languageCode: '{language}'"]
        if script:
            subtags.append(f"scriptCode: '{script}'")
        if region:
            subtags.append(f"countryCode: '{region}'")
        locale = f"Locale.fromSubtags({', '.join(subtags)})"
        literal = json.dumps(name, ensure_ascii=False).replace("$", r"\$")
        lines.append(f"  '{tag}': ({locale}, {literal}),")
    lines.append("};")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = {
        MESSAGES_TARGET: generate_messages(),
        LOCALES_TARGET: generate_locales(),
    }
    for path, expected in outputs.items():
        if args.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != expected:
                parser.error(f"outdated generated message source: {path}")
        else:
            path.write_text(expected, encoding="utf-8")

if __name__ == "__main__":
    main()
