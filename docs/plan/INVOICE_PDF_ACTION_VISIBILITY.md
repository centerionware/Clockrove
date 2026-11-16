# Invoice PDF action visibility

## Finding

The PR #45 merge validation on API 35 failed in the real invoice billing journey:
the generated invoice was saved, but “View original PDF” was below the initial
invoice detail viewport and could not be revealed reliably by the test. API 24
passed. This was a real discoverability issue, not a missing document or PDF
generation failure.

## Change

Move “View original PDF” directly below the invoice totals card so it is
available near the top of invoice details. Keep the real Android invoice
journey and assert that the action is tappable without scrolling before opening
the saved PDF.

## Validation evidence

No local build or test was run. CI validation is pending for the focused PR.
Record its exact head SHA, Actions run, API 24/35 result, and any unresolved
device issue here after GitHub Actions completes.
