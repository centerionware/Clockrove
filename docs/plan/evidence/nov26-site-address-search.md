# November 26 worksite address lookup evidence

Product contract: [app_plan.md](../../../app_plan.md)

This file preserves the detailed evidence for the work summarized in
`plan_status.md`.

- PR #103 exact code head `e00f1a5` / Validate run
  [45894307235](https://github.com/centerionware/Clockrove/actions/runs/45894307235)
  passed formatting, analysis, 321 Flutter behavior tests with 14 skipped,
  Android debug/release builds, 786 native tests with zero
  failures/errors/skips, all Android API 24 and API 35 journeys, 14
  actual-document viewer tests, and locked release tooling. The documentation
  site workflow also passed.
- The **Choose site centre** picker now offers an explicit address lookup.
  Nothing is sent until the user submits a query. The keyless request goes to
  `https://nominatim.openstreetmap.org/search` with a Clockrove identifying
  User-Agent and the resolved UI language. It is rate-limited to one request per
  second, keeps up to 20 successful queries in memory, and shows at most six
  results.
- The lookup is independent of tile selection, so it works with the default OSM
  tiles and a configured custom HTTPS tile provider. Selecting a result only
  moves the map centre; saving the site still requires **Use this centre** and
  **Save site**. No OSM account or API key is needed.
- The picker displays localized search, progress, empty, too-short,
  rate-limited and unavailable messages, plus the Nominatim privacy notice and
  OSM result attribution. The ten new messages exist in all 17 Flutter catalogs;
  the localization audit found only technical identifiers and network header
  literals in the new files.
- Tests cover the request shape and headers, parsing, malformed rows,
  caching/rate limiting, short/empty/unavailable results, selected-result
  centring, and custom-provider mode. The first run at `e1c6779` failed one
  test-only locale assertion (`en-US` from the bare test app versus the resolved
  catalog `en`); the widget test now supplies the same English locale that
  production resolves.
- Owner-device search against the live Nominatim service, including no-account
  behavior and provider-network review, remains open.
