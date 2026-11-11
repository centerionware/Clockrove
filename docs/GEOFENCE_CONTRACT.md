# Native geofence prototype contract

TimerRepository remains the serialized writer. GeofenceStore is a SQL helper
called under that boundary, never a second database owner. Schema 4 retains all
prior sessions, measured points, command outcomes and pending departures.

Site/settings mutations require command ID and expected version. Their results
share the timer command ledger. Capacity is checked across every enabled job,
before edits inside the same transaction. Rejected commands preserve settings and
registration keys; unexpected storage failures roll back the transaction. Successful
geometry/enabled/settings changes rotate registration IDs and disarm the job;
label edits preserve current facts. Site IDs cannot change jobs. The inventory
returned by sitesToRegister is requested configuration, not platform registration
proof. A future adapter must report the actual registration task result separately.

Native events carry an ID, registration IDs, definite presence, observed time and
reception time. They store membership facts without an off-clock GPS trail.
Duplicate delivery replays the original primary outcome (version, started session,
conflict and choice-required flag). Choice IDs and warning registration IDs are
transient hints and are omitted from stored receipts. Callers must honor replayed
and query current state before starting services or posting choices. Old registration
IDs are ignored. Pre-configuration observations cannot establish a new transition.
Events older than manual actions or contradictory/newer observations require review.

Enabling while inside does not start work. Known outside state arms a subsequent
qualified entry. Manual stop/switch suppresses the previous job while inside or
uncertain; an already-known outside job still requires a later fresh entry. Explicit
manual start is always allowed subject to normal time/overlap checks. Auto-entry
never switches an active session. Multiple eligible jobs yield choices for a
notification/deep link, not a background activity launch.

The final known exit among a job's enabled sites sets its detected departure.
Overlapping/unknown sites do not fabricate departure. Timely re-entry cancels a
candidate even when received after expiry, if finalization has not committed.
Due finalization records the detected exit, prunes later points and retains the
original deadline for late-event review. Zero grace finalizes without an intentional
wait; paid travel does not create a candidate. Manual work clears old candidates.
A timely re-entry received after finalization creates review state instead of
silently reopening or manufacturing work. Explicit acceptGeofenceReview preserves
recorded history, rotates registrations and waits for new qualified exit/entry;
future correction UI must let the user review those existing boundaries first.

This source layer has no platform geofence receiver/registration, background
permission UI, alarm scheduling or review notification yet. Those adapters must
join this boundary, use inexact scheduling and reconcile persisted deadlines on
recovery. No exact-alarm permission or periodic GPS timer is justified by this
contract. Native unit tests do not prove Android delivery, ten-minute sampling,
reboot/force-stop continuity, physical boundaries or a completed application.

Event IDs and fingerprints are device-keyed HMAC-SHA256 digests, with a key held
in Android Keystore. The event ledger retains neither raw off-clock event times
nor site/choice identifiers; it is excluded from future user backup data. Current
membership facts remain necessary for safe aggregation, while review state keeps
one current alert per job and removes its operational detail on explicit acceptance.
This is data minimization, not a claim that the device or audit history is tamper-proof.
Native unit tests inject a public synthetic HMAC fixture; they do not prove the
Android Keystore provider. Key persistence across process death/upgrade and
honest handling of unavailable keys still require device/adapter validation.

API-24 configuration validation runs before writes, avoiding raw SQLite savepoint
control through Android's transaction API. Primary source:
[Android 7 statement classification](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-7.0.0_r1/core/java/android/database/DatabaseUtils.java#L1394)
and [transaction dispatch](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-7.0.0_r1/core/java/android/database/sqlite/SQLiteSession.java#L865).

If receipt-key access fails, the writer records current degraded review state and
blocks automatic finalization, preserving a possibly unprocessed re-entry for
manual review. Manual stop remains independent of the key. A synthetic provider
failure test covers this policy; actual device key/provider handling remains a
physical validation requirement.
