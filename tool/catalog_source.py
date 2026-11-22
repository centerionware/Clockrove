"""Read the flat, double-quoted localization source files."""

import json


def read_catalog(path):
    messages = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, value = line.split(": ", 1)
        if key in messages:
            raise ValueError(f"{path}: duplicate key {key}")
        messages[key] = json.loads(value)
    return messages
