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
Manual/newer facts win over obsolete callbacks. Contradictions affecting recorded
work require review; a pre-manual callback cannot create an idle approval loop.

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

Platform geofence receiver/registration, background permission UI and review
notification remain pending. The departure wake-up adapter described below joins
this boundary; platform registration must reconcile persisted state on recovery. No exact-alarm permission or periodic GPS timer is justified by this
contract. Native unit tests do not prove Android delivery, ten-minute sampling,
reboot/force-stop continuity, physical boundaries or a completed application.

Event IDs and fingerprints are device-keyed HMAC-SHA256 digests, with a key held
in Android Keystore. The event ledger retains neither raw off-clock event times
nor site/choice identifiers; it is excluded from future user backup data. Current
membership facts remain necessary for safe aggregation, while review state keeps
current alerts only for affected work sessions and removes its operational detail on explicit acceptance.
This is data minimization, not a claim that the device or audit history is tamper-proof.
Native unit tests inject a public synthetic HMAC fixture; they do not prove the
Android Keystore provider. Key persistence across process death/upgrade and
honest handling of unavailable keys still require device/adapter validation.

API-24 configuration validation runs before writes, avoiding raw SQLite savepoint
control through Android's transaction API. Primary source:
[Android 7 statement classification](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-7.0.0_r1/core/java/android/database/DatabaseUtils.java#L1394)
and [transaction dispatch](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-7.0.0_r1/core/java/android/database/sqlite/SQLiteSession.java#L865).

If receipt-key access fails, the writer records current degraded review state and
blocks automatic finalization of affected work, preserving a possibly unprocessed
re-entry for review. Manual stop remains independent of the key. A synthetic provider
failure test covers this policy; actual device key/provider handling remains a
physical validation requirement.

Invalid event clocks and finite registration/permission/provider/recovery problem
codes use the same review hold. Upcoming native adapters must report these faults
through geofenceProblem rather than interpreting missing callbacks as departure.

Idle faults reset/disarm current membership and recover through fresh outside and
entry observations, without demanding history review when no work was affected.
A new timely return cancels grace and clears uncertainty for that active session.
Manual stop/switch resolves uncertainty for the session being explicitly closed,
while alerts for older finalized work remain available for correction. Expiry is
guarded per session so a historical alert cannot block newer manual work.

Configuration generation and current-fact freshness use separate barriers. A
provider recovery can discard stale current membership without discarding a late
entry that may correct previously finalized work from the same registration.
Only a real configuration/review reset invalidates that old registration context.

Historical ambiguity is sticky until explicit review. A later transient fault,
known timely return or manual stop cannot erase a late conflict about an earlier
part of the work record. Clearing current processing uncertainty is separate from
accepting ambiguous history. New manual work remains independent of older alerts.

## Departure wake-up adapter

`DepartureAlarmController` schedules one inexact `RTC_WAKEUP` alarm using
`setAndAllowWhileIdle`. It requests no exact-alarm permission and does not sample
location. The explicit, immutable broadcast token contains the affected session
and persisted deadline. Every delivery checks those values, current work, due
time and review state inside the native serialized repository before finalizing.
Re-entry/manual changes cancel the scheduled wake through a process-lifetime
commit observer; an already delivered stale intent remains harmless. Late Android
delivery preserves the observed exit boundary and prunes later measurements.
Reopening re-reads the durable candidate; no Dart countdown owns expiry.

Private boot/time-change receivers hold uncertain work for review and disarm
current membership rather than treating reboot or a clock edit as departure proof.
Held work has no repeatedly rescheduled alarm, and manual stop remains available.
Physical wake latency, process death, reboot and force-stop behavior remain device
gates. Platform geofence registration/callbacks and permission/site/review UI still
need integration before users can enable this automation.

Platform basis: [inexact Android alarms](https://developer.android.com/develop/background-work/services/alarms).
Android may delay delivery, including during power-saving modes; no precise grace
expiry guarantee is presented to the user.

## Android geofence platform adapter (pending Actions/device evidence)

`AndroidGeofencePlatform` removes this app's previous PendingIntent inventory
before adding at most 100 desired sites. Registration IDs are the repository's
configuration-generation UUIDs. Initial ENTER/EXIT observations establish facts;
they do not bypass outside-then-entry arming. The callback token is explicit and
mutable as required by Play Services, and the receiver is private. Precise and,
on API 29+, background location are checked separately from manual sampling.
The manifest declares background access; a purpose-specific activation/settings
flow remains to be implemented, so the app does not request it automatically.

`GeofenceRegistrationController` distinguishes desired inventory from successful
platform completion. An obsolete in-flight result cannot report a newer
configuration ready. Failures hold affected work and require a configuration
change or explicit foreground retry, avoiding a provider-error retry loop.
An app-private boolean remembers that cleanup may be needed; it stores no sites,
keys or locations and is not a success receipt. A fresh install with no enabled
sites never initializes the Play Services client. Reopening after a prior request
holds recovery uncertainty before processing an already delivered expiry alarm.

Geofence callbacks and departure alarms share one native executor. Definite
ENTER/EXIT with a triggering-location timestamp enter the repository; missing,
future or unsupported measurements and provider errors hold automation. Callback
coordinates never enter off-clock history. Duplicate receipts suppress repeated
service/choice-notification effects. A qualified start rechecks the active session
and permission before requesting the location foreground service; Android startup
restrictions remain explicit health failures. Ambiguous idle entries post a generic
notification opening the existing manual job selection, and never auto-switch.

Synthetic tests inject platform task completions and a public HMAC fixture; they
cannot prove real Play Services registration, delivery or Android FGS exemptions.
UI activation/review/health and physical-device acceptance remain outstanding.
Primary platform references: [geofences](https://developer.android.com/develop/sensors-and-location/location/geofencing),
[background location](https://developer.android.com/develop/sensors-and-location/location/permissions/background),
[foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## Site centre selection

The site form supports coordinates, explicit map selection and an explained
one-shot current-location request. The OSM picker uses the existing cache,
identification and shared backoff policy; attribution stays visible on failures.
Unavailable tiles and unsupported polar rendering preserve coordinate entry.
Choosing a centre updates the draft only; saving is a separate versioned command.

Setup location requires a foreground activity and precise permission. It uses
`getCurrentLocation` with zero historical cache age, a 15-second provider duration
and a cancellable 20-second UI deadline. Missing, stale, future or malformed measurement
payloads fail without inventing a point. The draft shows actual measured time and
accuracy. App backgrounding, editor cancellation or activity detach cancels the
request; late callbacks cannot complete it again or replace a newer request.
Neither this controller nor its provider owns a repository/history writer.
Synthetic provider/codec tests are distinct from real fused-location validation.
Platform basis: [current location request parameters](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder).
