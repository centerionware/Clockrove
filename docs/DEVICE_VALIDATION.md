# Prototype device validation

Install the latest successful main build from the owner's Play internal-test
listing. Confirm the version in Android App info and record its matching Play
delivery run. An accepted upload does not establish installation or behavior.

Record device model, Android version, build version, location permission mode,
notification permission, battery saver and battery optimization settings. Keep
real coordinates/screenshots and detailed traces in ignored `device-evidence/`;
publish only sanitized outcomes and timing differences.

| Check | Procedure and evidence |
| --- | --- |
| Manual timer | Create two synthetic jobs. Start one, switch and stop. Verify one active session and state after closing/reopening the app. Repeat offline and with denied location. |
| Main Settings regression | Use a delivered build containing #34 and record its App info version. Save valid main Settings, confirm Android does not close the app, reopen and verify persistence. Put an unknown placeholder in the email subject and an unfinished placeholder in the body: Save must show both field errors, retain the draft and keep saved values intact. Correct both and save again. Repeat with the keyboard open, light/dark themes and large text; job-settings saves must still work. |
| Invoice sequence and templates | In a dedicated synthetic test installation, set a rate and create finalized work. Prepare the first two invoices and verify INV-1000 then INV-1001, correct expanded subject/body, totals and PDF attachment. On an upgrade, verify existing prepared invoices retain their exact numbers/content and a higher sequence continues. Invalid templates must block preparation without consuming a number. |
| Manual history | Add/correct/delete closed synthetic work; confirm overlap rejection across jobs and that running entries must be stopped first. Review changes including deleted entries. During a repeated local hour choose each explicit UTC offset in separate entries; a missing local time must be rejected. Confirm report/CSV totals and pruning of real points outside shortened bounds; extensions must not invent samples. |
| Widget independence | Add widgets, start in app, remove the app from recents and stop using a widget. Reopen and confirm the committed result. Add Timer, Tasks and Combined separately. Configure different active defaults and picker mode. A native default clock-in must work with Flutter closed and location denied; a missing/archived default must open the picker without starting work. Small Combined shows timers; resize larger and rotate to see scrollable overdue/upcoming tasks. Scroll to the last item and tap it: the correct job board must open without starting a timer or replacing an unsaved main-app form. Completed/archived tasks must disappear after commit. Reconfigure, remove, and restore widgets; work must remain intact and restored device-local IDs must never start timers. Removing recents is not the same as force-stop. |
| Ongoing notification | Start a timer, enable location after the purpose prompt, then clock out through the notification with the app backgrounded. Verify all surfaces agree. Repeat with notification permission denied; manual clock-out must remain available. |
| Observed location | Outside with permission granted, record requested start time and actual latest measured sample time/accuracy. Keep the screen off for at least 40 minutes, reopen and record actual timestamps. Missing samples are gaps, not synthetic data or departure proof. |
| Degraded operation | Deny, approximate-only, revoke in Settings, disable device location, and enable battery saver separately. Record displayed health and verify manual clock-out still works. |
| Recovery | Separately remove from recents, terminate the process, reboot, and Android force-stop/relaunch. Record persisted active timer and actual service state; continued background execution after force-stop is not promised. |
| Visuals | Check light/dark themes, largest comfortable text size, long job names, widget readability, launcher mask/themed icon, cold/warm launch and keyboard/dialog behavior. |

Geofence registration, automatic entry/suppression, pending-departure scheduling,
coordinate site editing, permission/review UI and a stored-point map now have source
and Actions evidence. Their real-device recovery/delivery matrix remains unproven.
Map-tap and one-shot current-position site setup are being validated separately.
Repeat the full matrix after those implementations. Use at least one stock Android
and one device with aggressive battery management before release.

For each row record pass/fail/untested, expected behavior, actual result, timestamp
and sanitized reproduction steps. A screenshot of a running timer alone does not
prove sampling cadence, engine-independent actions or recovery.

After native geofence adapters are connected, also verify that the device-local
Keystore receipt key survives process death and app upgrade. An unavailable key
must surface degraded automation while manual timekeeping stays available. CI's
synthetic HMAC fixture does not establish device Keystore behavior.

The departure alarm adapter now has source and planned synthetic API-24/API-35
coverage, pending Actions validation. After platform registration is connected,
verify a grace alarm with the screen off and battery saver/Doze: record actual wake
latency and ensure the session ends at the observed exit, with later points removed.
Re-enter/manual stop before grace, then allow a stale alarm to arrive; it must not
close current/new work. Reboot and edit the wall clock during grace: uncertainty
must hold automatic expiry without an alarm loop, and manual clock-out must work.
No exact-alarm access should be requested. Android force-stop is a separate case;
relaunch must preserve work and reconcile, not claim uninterrupted automation.

For site setup, select by coordinates, map tap and current location separately.
Repeat off-clock and while a work session is active. A setup fix may update the
site draft but must never insert a work-history sample. Deny precise permission,
disable location, leave the app during the request, cancel the editor and simulate
an unavailable provider. The request must finish/cancel honestly; map/coordinate
setup and manual timing must remain available. Actual accuracy/time shown must
match the supplied fix. OSM requests require live network evidence, not CI tiles.

After recovery retry/upgrade source is validated, disable then restore the location
provider while automation is enabled. Retry site monitoring from the foreground;
request submission must not be shown as registration success. With a pending
departure and an active session, install a newer build signed with the same upload
key: verify work/settings remain, uncertain expiry is held, and only previously
enabled sites are requested again. This requires actual upgrade/device evidence.
