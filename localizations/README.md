# Clockrove translations

The app reads UTF-8 YAML catalogs from this directory. Each locale has its
own file, named by its BCP 47 language tag, such as `es.yaml` or
`zh-Hant.yaml`. Edit a value to change the translation for that locale.

When migrating a message, give it a stable key in
`lib/l10n/app_localizations.dart`, add that key to `en.yaml` and every
shipped catalog, and look it up with `AppMessage.messageName.of(context)`.
The English enum value is also the fallback for individually mounted screens.
Keep dynamic values such as names or amounts out of translation keys. Preserve
user-entered text, document identifiers, and stored currency codes as entered.

Register additional locales with their language names in `appLanguages`. Use
script-specific tags where the written language requires one. The shipped
catalog test checks every registered locale for missing, extra, or empty keys.
Run validation in GitHub Actions.

Settings uses the phone’s language preferences by default. An explicit language
is saved on the Android installation. English is the fallback when the device
has no supported language. The first migration covers language selection and
some Settings and timekeeping labels. Remaining Flutter screens, Help, native
notifications and widgets, and generated document labels still need migration.

GitHub Actions uploads a localization audit on each validation. It inventories
likely untranslated Dart and Kotlin literals, hard-coded Android XML labels,
and missing Android resource keys for each shipped locale. Review the report
before moving strings because it also includes technical literals and cannot
prove that all dynamically constructed messages reach a catalog. The
`strict_localization` workflow input makes findings fail validation. The catalog
parity test runs on every validation.

Android resource copy lives in `android/<language-tag>.yaml` within this directory.
Use flat YAML maps with JSON-style double-quoted values and Android positional
format arguments such as `%1$s`. Run `python3 tool/android_catalog.py` to
regenerate checked-in Android resources. Actions checks locale and key parity,
format arguments, and whether the generated XML matches the catalogs. Installed
widgets currently follow the Android resource locale.
