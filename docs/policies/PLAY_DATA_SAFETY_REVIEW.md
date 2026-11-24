# Clockrove Google Play Data safety review

Google Play requires the Data safety form for closed testing. The form
covers transfers by the app, its libraries and third-party SDKs. Use the
[Play Console guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
to classify the flows below against the signed release candidate.

| Flow | What the source shows | Evidence for the release declaration |
| --- | --- | --- |
| Work records, photos and documents | Jobs, work history, business records and attachments use app-private storage. There is no Clockrove account or sync backend. | Review the merged manifest and dependency list. Observe a synthetic offline timekeeping and photo session. |
| Work location and site automation | Work location history is recorded on-device when enabled. The Android integration uses Google Play services for fused location and geofencing. | Confirm permissions and disclosure text, inspect SDK data handling and measure release network behavior before classifying location transfer. |
| Default and custom maps | The selected HTTPS tile provider receives tile coordinates, zoom and connection metadata. Custom credentials are sent to the selected provider. | Verify default and custom requests and provider retention details. Account for the viewed area, including when a map opens on a saved work location. |
| Android document picker and sharing | Users choose storage destinations and recipient apps. Backups are encrypted before export. CSV/PDF sharing is user-directed. | Confirm the actual picker and sharing flows and assess the form's applicable user-directed transfer rules per data type. |
| Deletion and settings | Users can disable GPS recording, delete saved GPS ranges and clear local app storage. Exported copies remain at their destinations. | Verify retention and deletion behavior in the candidate and match the privacy policy to those controls. |

Release handoff needs the reviewed form responses, the public privacy URL
(`website/legal/privacy.html` once the site is deployed), matching in-app policy
text and the Play Console submission status. Record
the app version and release commit alongside the signed-build evidence.
Use the [map and location inventory](../MAP_PRIVACY_DATA_INVENTORY.md)
and [privacy draft](PRIVACY_POLICY_DRAFT.md) for the source details.
