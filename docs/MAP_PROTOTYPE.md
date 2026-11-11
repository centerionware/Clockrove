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
provider configuration remain pending. The Google runtime-key experiment is
still blocked on credentials/device evidence; no Google plugin is selected yet.

Sources reviewed for the selected implementation:

- [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/)
- [flutter_map caching](https://docs.fleaflet.dev/layers/raster-tile-layer/caching)
- [8.3.2 cache metadata](https://github.com/fleaflet/flutter_map/blob/v8.3.2/lib/src/layer/tile_layer/tile_provider/network/caching/tile_metadata.dart)
- [8.3.2 conditional network loading](https://github.com/fleaflet/flutter_map/blob/v8.3.2/lib/src/layer/tile_layer/tile_provider/network/image_provider/image_provider.dart)
