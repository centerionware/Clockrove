# Invoice PDF action visibility

## Finding

The PR #45 merge validation on API 35 failed in the real invoice billing journey: the generated invoice was saved, but “View original PDF” was below the initial invoice detail viewport and could not be revealed reliably by the test. API 24 passed. This was a real discoverability issue, not a missing document or PDF generation failure. Main validation run [43628166015](https://github.com/centerionware/Clockrove/actions/runs/43628166015) therefore failed, and guarded Play delivery [43630004858](https://github.com/centerionware/Clockrove/actions/runs/43630004858) was skipped because delivery requires successful main validation.

## Change

PR #70 moves “View original PDF” directly below the invoice totals card so it is available near the top of invoice details. The real Android invoice journey now asserts that the action is tappable without scrolling before opening the saved PDF.

## Validation evidence

PR #70 exact head 58ca416dfe8c3b82fff8d58898db9ca7acc86a26 passed full Validate in [run 43798809470, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43798809470/attempts/2), against base eb07857ed24344c4d7cbe4bd3e04d9ff5a33b4a7. The actual Android suite passed 38 tests on each of API 24 and API 35, with zero failures, errors, or skips. The invoice journey confirmed the PDF action was tappable before scrolling and opened the generated PDF in the real Android viewer. Native tests, Flutter behavior checks, build checks, artifact export, and release-tooling checks also passed.

Attempt 1 of the same run stopped at Android APK installation timeouts before the affected device journey ran. The bounded exact-head rerun completed successfully; no app or test-runner change was made in response to the install timeout.

PR #70 was merged by the owner as main commit 025cc10f5aac80ce6f56005f694e448ee4473761. Merged-main Validate [43804094163](https://github.com/centerionware/Clockrove/actions/runs/43804094163) passed on that exact commit. Play delivery [43806883748](https://github.com/centerionware/Clockrove/actions/runs/43806883748) accepted internal submission of `0.1.0-internal.53.1` / version code `216968203`. This confirms upload/submission only, not review approval or tester availability. No local build or test was run.
