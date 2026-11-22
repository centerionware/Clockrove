"""Read the flat, double-quoted localization source files."""

import json
import re
from pathlib import Path

LOCALES = Path("localizations/metadata/languages.yaml")
LANGUAGE_TAG = re.compile(r"([a-z]{2,3})(?:-([A-Z][a-z]{3}))?(?:-([A-Z]{2}|[0-9]{3}))?")


def read_catalog(path):
    messages = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, value = line.split(": ", 1)
        if key in messages:
            raise ValueError(f"{path}: duplicate key {key}")
        messages[key] = json.loads(value)
    return messages


def locale_names():
    names = read_catalog(LOCALES)
    if next(iter(names), None) != "en":
        raise ValueError(f"{LOCALES}: list English first as the default locale")
    for tag, name in names.items():
        if not LANGUAGE_TAG.fullmatch(tag) or not isinstance(name, str) or not name.strip():
            raise ValueError(f"{LOCALES}: invalid locale entry {tag}")
    return names
