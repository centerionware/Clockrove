# Default-map feasibility

The prototype opens the current session's latest stored work observation. It does
not request a new reading, create idle history, draw a verified route or edit sites.
Coordinates, measured time and accuracy cross the typed tracking adapter together;
late callback order must not replace the newer measured observation.

Default rendering uses pinned flutter_map 8.3.2 and the exact HTTPS OSM tile URL.
The native client sends a stable Clockrove User-Agent/contact URL. Visible tiles
only (`panBuffer: 0`); no bulk download or prefetch controls. Built-in caching has
a 100 MB soft limit, respects server freshness/conditional requests and falls
back to seven days where freshness metadata is missing. The OS may clear caches.
429/503 Retry-After responses pause subsequent requests to the same origin across
map screens for the app process; invalid/missing deadlines use at least 30 seconds.
There is no retry loop or background tile fetch. Closing the map closes its client.

Attribution is always visible and links to OSM copyright information. Tile failure
shows a plain message; Back returns to the independent timer. Map requests reveal
the viewed area to the provider. Public tiles are best-effort, with no availability
promise. No API key or account is required for this default provider.

CI uses synthetic tile bytes and injected HTTP responses, never public tile loads.
Synthetic map previews prove layout and overlays, not live OSM availability or
native GPS. Device/network proof, complete reports/site editing and optional
provider configuration remain separate release evidence. Google Maps remains
an open optional provider, not an implemented feature. Google's documented
[Flutter configuration](https://developers.google.com/maps/flutter-package/config)
and [Android project setup](https://developers.google.com/maps/documentation/android-sdk/config)
put the key in Android application manifest metadata; the documented
[`MapsInitializer` API](https://developers.google.com/android/reference/com/google/android/gms/maps/MapsInitializer)
has no runtime key parameter. No supported runtime-key path has been identified
for the standard Flutter/Android SDK integration, and a native platform view
would still use that SDK. Do not add a Google key field or claim availability
until a supported path is established and proven with an owner-provided,
restricted key. This does not affect keyless OSM or the existing custom HTTPS
raster provider.

Sources reviewed for the selected implementation:

- [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/)
- [flutter_map caching](https://docs.fleaflet.dev/layers/raster-tile-layer/caching)
- [8.3.2 cache metadata](https://github.com/fleaflet/flutter_map/blob/v8.3.2/lib/src/layer/tile_layer/tile_provider/network/caching/tile_metadata.dart)
- [8.3.2 conditional network loading](https://github.com/fleaflet/flutter_map/blob/v8.3.2/lib/src/layer/tile_layer/tile_provider/network/image_provider/image_provider.dart)

Validation: full Actions run 43006507700 on 94451c2 passed; PR #10 merged at bb2a9eb.
Six synthetic previews in artifact 13849240735 were reviewed in both themes.
The checked journey returns from a failed map to the actual TimerScreen and
successfully stops the same running session. This is UI/native-unit evidence,
with the runtime/provider/device limitations above still in force.
