"""Read the flat, double-quoted localization source files."""

import json
import re
from pathlib import Path

LOCALES = Path("localizations/metadata/languages.yaml")
LANGUAGE_TAG = re.compile(r"([a-z]{2,3})(?:-([A-Z][a-z]{3}))?(?:-([A-Z]{2}|[0-9]{3}))?")


def _read_flat_catalog(path):
    messages = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, value = line.split(": ", 1)
        if key in messages:
            raise ValueError(f"{path}: duplicate key {key}")
        messages[key] = json.loads(value)
    return messages


def read_catalog(path):
    messages = _read_flat_catalog(path)
    if path.parent != Path("localizations") or path.suffix != ".yaml":
        return messages
    overlays = path.parent / "flutter" / path.stem
    if not overlays.is_dir():
        return messages
    for overlay in sorted(overlays.glob("*.yaml")):
        for key, value in _read_flat_catalog(overlay).items():
            if key in messages:
                raise ValueError(f"{overlay}: duplicate key {key}")
            messages[key] = value
    return messages


def locale_names():
    names = read_catalog(LOCALES)
    if next(iter(names), None) != "en":
        raise ValueError(f"{LOCALES}: list English first as the default locale")
    for tag, name in names.items():
        if not LANGUAGE_TAG.fullmatch(tag) or not isinstance(name, str) or not name.strip():
            raise ValueError(f"{LOCALES}: invalid locale entry {tag}")
    return names
