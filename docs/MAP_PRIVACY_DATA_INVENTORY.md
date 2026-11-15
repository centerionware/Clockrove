# Map and location data inventory

This is an implementation inventory for privacy and release review. It is not a
privacy policy, legal assessment, or store disclosure. Recheck it against the
release candidate and selected tile provider before publication.

## Data and handling

| Data | Where it is used or stored | Sent outside the app | Release evidence still needed |
| --- | --- | --- | --- |
| Work coordinates, measured timestamp and accuracy | Stored with work-location history in the local app database while work is recorded; shown in maps/reports and available in the location export | A map request sends viewed tile coordinates/zoom and ordinary network metadata to the configured tile provider. Clockrove does not send its location-history rows as a tile request | Confirm actual OSM/custom-provider requests on a device and review retention/deletion UX and store disclosures |
| Default OSM tile request | Requested over HTTPS from `tile.openstreetmap.org`; attribution links to OSM copyright | OSM receives requested tile coordinates and network metadata. No account or API key is used | Confirm live tile load and attribution on supported device/network configurations |
| Custom raster provider template and attribution | Saved in app-private settings | Tile coordinates and network metadata go to the user-selected host | Confirm provider identity, terms, attribution and privacy policy before configuration |
| Custom provider authentication header | Android stores the configuration encrypted with AES-GCM under an Android Keystore key; public settings expose only credential presence, while a separate channel supplies the value for tile requests | The configured header goes to the chosen provider. Redirects are disabled so credentials cannot be forwarded to another host | Recheck Keystore round-trip, reset, redaction and redirect behavior in Actions/device evidence; confirm the selected provider's terms |
| Google Maps key and map requests | Google Maps is not an implemented provider. Current settings do not request or accept a Google SDK key | Clockrove's map screens make no Google Maps requests | The documented Android setup uses application manifest metadata for the key; no supported runtime-key path has been identified. Do not add a Google key field/provider until a supported runtime path is established and exercised with an owner-provided restricted key. Any build-time-key alternative requires an explicit contract decision |
| Tile cache | `flutter_map` cache is local to the app and Android may clear it; no bulk download or prefetch is provided | Cache misses cause requests to the selected provider | Inspect release cache behavior and confirm relevant cached displays are cleared after location deletion as designed |

Map tile URLs, provider errors and credentials must not be written to logs or
diagnostics. Custom credentials are sent in headers, not URL parameters;
provider credentials and tile caches must remain excluded from reports and the
planned encrypted user backup. The backup is not implemented on the source
baseline used for this inventory. Map requests reveal viewed areas even when
the app does not send stored location history. OSM is best-effort; map failure
must not block manual timekeeping, reports or exports. No analytics or remote
crash reporting is part of the launch contract.

## Device acceptance checklist

- [ ] On a supported Android device, open a map with network access; verify
  loaded tiles, visible attribution, selected coordinates and the external-map
  action.
- [ ] Repeat offline and with a failed tile response; verify a useful error and
  working navigation/manual timekeeping.
- [ ] Configure a synthetic custom HTTPS tile endpoint and header credential;
  verify settings survive process death, only the selected host receives the
  header, redirects do not forward it, and reset removes the configuration.
- [ ] Inspect sanitized logs and diagnostics for credentials and sensitive tile
  URLs; do not retain real coordinates or personal backups in repository
  artifacts.
- [ ] Verify location-history deletion updates map/report displays and record
  cache behavior. Existing exports/backups are not retroactively erased.
- [ ] If a supported Google runtime-key path is established and approved, use an
  owner-provided restricted key to verify successful rendering and missing or
  invalid-key fallback without breaking OSM. Record billing/project ownership
  and Android signing identity; never add the key to Git or CI logs.

Record device/build, provider, network state, observed behavior and sanitized
evidence under the existing device-validation process. Synthetic tiles and
emulator Keystore tests do not substitute for physical provider evidence.

## Google Maps runtime-key feasibility

Google's [Flutter configuration guide](https://developers.google.com/maps/flutter-package/config)
and [Android project setup](https://developers.google.com/maps/documentation/android-sdk/config)
place the Android Maps SDK key in `AndroidManifest.xml` as
`com.google.android.geo.API_KEY`. The documented
[`MapsInitializer` API](https://developers.google.com/android/reference/com/google/android/gms/maps/MapsInitializer)
initializes the SDK using an Android `Context` and has no API-key argument.
Therefore the standard Flutter/Android SDK integration has no documented
runtime user-key initialization path. A platform-view wrapper does not change
the SDK's authentication contract. Keyless OSM and the existing custom raster
provider remain available while this requirement is open.
