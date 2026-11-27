# Open Zillow from a work site (November 27, 2026)

## Scope

- The site editor shows **Open Zillow** only while the latitude and longitude
  fields both contain finite, in-range coordinates.
- Tapping it opens Zillow's homes URL for those coordinates in the external
  browser. No Zillow account, API key, embedded map, or server call is added.
- The label and failure message are in `site_editor.yaml` for all 17 supported
  languages.
- The map privacy/data inventory records the user-directed coordinate transfer.

## Validation

- `test/zillow_link_test.dart` covers the exact coordinate URL and rejects
  non-finite or out-of-range values.
- `test/site_selection_test.dart` covers the button appearing after valid
  coordinates and disappearing when the longitude is no longer valid.
- `python3 tool/flutter_catalog.py --check` and repository-wide `dart format`
  passed.
- No local Flutter test, analyzer, Android build, or device browser check ran,
  per repository instructions. CI is authoritative; Zillow's rendering and the
  real browser handoff remain unverified on a device.
