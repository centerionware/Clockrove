# Settings invoice preview language

## Report

The sample invoice opened from main Settings stayed in English while the app was set
to another language.

## Cause

The native renderer resolved its labels from the saved language preference only. The
preview screen never told it which locale the screen was actually showing, so any
state where Flutter's resolved locale and that preference disagree (Android per-app
language, a change that has not been committed, a cleared override on a phone whose
language differs) produced an English document next to a translated screen.

## Change

- `localizedContext(context, languageTag)` accepts the locale the UI is showing and
  still falls back to the saved preference, then the device resources.
- The preview screen sends `Localizations.localeOf(context).toLanguageTag()` with
  `settingsInvoicePreview`; it re-renders if the locale changes while it is open.
- `InvoiceDocuments.settingsPreview` renders the sample through that locale, and the
  sample snapshot is exposed for tests.

## Verification

- `InvoicePreviewLanguageTest` (Robolectric, API 24 and 35) asserts the sample is
  English with no override, Spanish for an explicit `es`, and that an explicit `de`
  wins over a stale `es` preference.
- `test/business_settings_test.dart` asserts the preview screen passes the UI locale
  to the typed report API.
- Not verified on a device: the visual result in a non-English app language. Physical
  device validation remains open.
