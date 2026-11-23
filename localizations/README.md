# Clockrove translations

The app reads UTF-8 YAML catalogs from this directory. Each locale has its
own file, named by its BCP 47 language tag, such as `es.yaml` or
`zh-Hant.yaml`. Edit a value to change the translation for that locale.

When migrating a message, add its stable key to `en.yaml` and every shipped
catalog. Run `python3 tool/flutter_catalog.py` to regenerate the typed Flutter
keys, English fallback, and overlay manifest, then look it up with
`AppMessage.messageName.of(context)`. Flutter loads each locale's root catalog
and its registered overlay files at runtime. Edit message values in YAML.
Actions checks that generated Flutter sources match the catalogs.
Keep dynamic values such as names or amounts out of translation keys. Preserve
user-entered text, document identifiers, and stored currency codes as entered.

If a locale file approaches the 1,000-line maintainability limit, add messages
to a focused YAML file under `flutter/<language-tag>/`. `pubspec.yaml` includes
these overlays as app assets, and the generated manifest loads them with the
root catalog. The generator merges overlays when producing typed keys and
rejects duplicate keys. Keep each overlay below 1,000 lines and update every
shipped locale together.

Register additional locales with their names as shown to speakers in
`metadata/languages.yaml`. Add both the Flutter and Android catalogs for the
same tag and regenerate both sets of resources. Use script or regional tags
where the written language requires them. The shipped
catalog test checks every registered locale for missing, extra, or empty keys.
Run validation in GitHub Actions.

The current catalog set covers 17 locales. Settings uses the phone's language
preferences by default. An explicit language is saved on the Android
installation. English is the fallback when the device has no supported
language. Full app copy coverage remains in progress; track uncovered surfaces
and validation evidence in `plan_status.md`.

GitHub Actions uploads a localization audit on each validation. It inventories
likely untranslated Dart and Kotlin literals, hard-coded Android XML labels,
and missing Android resource keys for each shipped locale. Review the report
before moving strings because it also includes technical literals and cannot
prove that all dynamically constructed messages reach a catalog. The
`strict_localization` workflow input makes findings fail validation. The catalog
parity test runs on every validation.

Android resource copy lives in `android/<language-tag>.yaml` within this directory.
Use flat YAML maps with JSON-style double-quoted values and Android positional
format arguments such as `%1$s`. When native Android code presents a message
that already exists in Flutter, point the Android key to `@flutter:messageKey`;
the generator resolves that locale's root and overlay catalogs and converts
named placeholders to Android positional arguments. Keep Android-only copy in
the native catalog. Run `python3 tool/android_catalog.py` to
regenerate checked-in Android resources. Actions checks locale and key parity,
format arguments, and whether the generated XML matches the catalogs. Widget contents and newly rendered tracking and geofence notifications honor
the saved Settings override. Android launcher and widget picker metadata
follow the system resource locale.
