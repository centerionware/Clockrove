# Open Zillow from a work site (November 27, 2026)

## Scope

- The site editor shows **Open Zillow** only while the latitude and longitude
  fields both contain finite, in-range coordinates.
- Tapping it reverse-geocodes those coordinates to a house-level address and
  opens Zillow's address search in the external browser. A street or locality
  is not treated as a property. If no house number is found, the job customer's
  saved address is used only as a fallback.
- No Zillow account, API key, embedded map, or Clockrove server is added. The
  reverse lookup goes directly to OpenStreetMap Nominatim over HTTPS.
- The label and failure message are in `site_editor.yaml` for all 17 supported
  languages.
- The map privacy/data inventory records the user-directed coordinate transfer.

## Validation

- `test/zillow_link_test.dart` covers address URLs, whitespace normalization,
  empty-address rejection, and property-before-customer fallback.
- `test/address_search_test.dart` covers the reverse request parameters and
  headers, exact-address composition, and rejecting a street without a house
  number.
- `test/site_selection_test.dart` covers the button appearing after valid
  coordinates and disappearing when the longitude is no longer valid.
- `python3 tool/flutter_catalog.py --check` and repository-wide `dart format`
  passed.
- Exact-head [run 46119471270](https://github.com/centerionware/Clockrove/actions/runs/46119471270)
  passed all static, Dart, Android/release, native and API 24/35 device stages.
- No local Flutter test, analyzer, Android build, or device browser check ran,
  per repository instructions. Zillow's real browser resolution and the
  property-address accuracy remain unverified on a device.
