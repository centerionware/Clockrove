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
| Exact amounts and task quantities | Use only synthetic work. Set one unit price of 2.55 and quantity 26: task/report/invoice line must be 66.30 before tax. Enter excess currency precision, an invalid quantity and an over-limit pasted value whose prefix looks valid: the complete draft must remain, with an error and no saved truncated amount. Verify global/job tax and rate overrides, including explicit zero. |
| Payments and receipts | Issue 100.00 plus 7.5% tax (107.50). Record 30.00: paid 30.00, due 77.50 and a saved partial receipt. Mark paid must propose 77.50, then show paid 107.50/due 0.00 with a paid receipt. Original invoice/PDF and number must remain unchanged. A paid invoice must not offer voiding; archived/deleted-job invoices must retain their payment history and copies. Reopen the app and verify committed balances rather than entering a duplicate payment. |
| Accounting email handoff | With addresses controlled by the tester, open invoice, partial/paid receipt and latest-balance drafts in the phone's default email app. Verify customer To, company CC when AR is blank, AR CC when configured, expanded subject/body and a readable matching PDF attachment. The same address must not be duplicated in To and CC. Cancel drafts unless an intentional delivery test is being recorded; a composer opening does not prove sending or delivery. Record email app/version and each actual outcome. |
| Invoice correction and credit | On a separate 107.50 invoice with 30.00 paid, open Void & replace. Change the unit price to 120.00 and tax to 10%: preview total 132.00, existing credit 30.00 and due 102.00. Cancel review first: no new number, credit or source change. Confirm: original becomes VOID with zero due; replacement has a new number and the same payment identity/date credited once. Open its original receipt and predecessor; old PDFs stay unchanged and historical email warns not to pay the voided invoice. Verify VOID copy/CC, rejection of totals below credit, and correction of a moved payment on its current invoice. |
| Work reservation corrections | Use two separate synthetic source records. Remove one line in a replacement: its source stays reserved unless explicitly released in Review work reservations. Release only the intended record, confirm and verify it becomes unbilled while retained work remains excluded. Add actual unbilled work by date and verify it appears once. Confirm an unpaid void releases only its own sources; paid invoices and payment recording must never release work. Check every resulting invoice total, credit and remaining balance. |
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
