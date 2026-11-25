# November 23 geofence departure follow-up

Product contract: [app_plan.md](../../../app_plan.md)

- PR #83, the explicit in-place clock-in flow, merged to main as
  `e126bf1c1119ad6ea968bfb2be0dd1c12d6fdf8d`. Exact-head validation
  [45088251947](https://github.com/centerionware/Clockrove/actions/runs/45088251947)
  and merged-main validation
  [45091148930](https://github.com/centerionware/Clockrove/actions/runs/45091148930)
  passed. Internal Play delivery
  [45094669012](https://github.com/centerionware/Clockrove/actions/runs/45094669012)
  submitted the bundle; this does not prove installation or device geofence
  delivery.
- The owner reports that automatic arrival failed at a job, so they clocked in
  manually and updated the app while still working. Later, after leaving, the
  timer did not clock out. They had a 1-minute grace, raised the radius to 150 m,
  waited about 20 minutes and tapped **Retry site monitoring**. Automation and
  location permissions were already enabled. They saw the GPS icon twice; this
  does not establish that Android delivered a geofence exit. A separate 150 m
  home test did auto-start after a delay, then was manually stopped while still
  at home, so it did not test departure. The owner clarified the 15 m to 150 m
  radius change followed the app update. The reported current-location save was
  before that update; the later edit kept the site location and changed only
  radius. No fresh fix was reported with that edit, so it does not exercise this
  follow-up. Monitoring health after retry remains unconfirmed.
- Source tracing found that site edits rotate registration keys and reset
  membership to `UNKNOWN`. Before this follow-up, a current fix saved with a
  site could not establish the baseline for a running session's departure. The
  in-progress correction passes the measured fix to native state, seeds
  membership only when accuracy supports the classification, creates no session
  or location-history point, and stores the fix's accuracy/time in a digest in
  the retry receipt. It also adds accuracy, active-session and no-false-departure
  regressions. Run
  [45103688030](https://github.com/centerionware/Clockrove/actions/runs/45103688030)
  passed formatting, Analyze, Flutter tests and Android builds, then found one
  native regression: label-only site rename incorrectly rotated the fence key.
  The condition now preserves the previous behavior. Corrected exact-head
  Validate run
  [45104682175](https://github.com/centerionware/Clockrove/actions/runs/45104682175)
  passed on `7b64341`: formatting, Analyze, Flutter tests, Android build and
  release Java, 757 native tests with zero failures/errors/skips, and 40 actual
  Android tests on each of API 24 and API 35 with zero failures/errors/skips.
  Owner-device callback acceptance is still pending. The earlier docs-only
  exact-head run [45095882626](https://github.com/centerionware/Clockrove/actions/runs/45095882626)
  did not validate this code.
