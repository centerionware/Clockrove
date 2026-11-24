# PR #82 validation recovery

Run [45249620359](https://github.com/centerionware/Clockrove/actions/runs/45249620359)
on `9c8043b` passed static checks, Dart analysis and 311 behavior tests, Android
and release compilation, all 759 native unit tests, release tooling, and API 24
device journeys. The Help navigation integration then failed on both APIs
because it searched for a widget absent from the manually composed test root.
The API 35 quote PDF test also found that a very long amount was clipped, which
prevented quote previews from being produced.

Run [45257518080](https://github.com/centerionware/Clockrove/actions/runs/45257518080)
on `eef2e4f` passed static, Dart, build, native, release, and API 24 checks. It
still failed Help navigation on both APIs, API 35 backup-picker navigation, and
wide quote PDF text. The picker looked for “Show internal storage,” which this
API 35 DocumentsUI does not expose. Its script exited before cleaning up, so
DocumentsUI remained foreground and caused later settings journeys, result
validation, captures, and API 35 app journeys to fail. Five quote viewer cases
failed only because the earlier quote PDF check stopped preview generation.

The follow-up changes route the synthetic picker fixture through Downloads,
clean the app and document-picker state on every exit, wait for the native Help
version before asserting it and drag the visible list, and split oversized PDF
words into at most 48 code points per line. These changes await a fresh exact-
head Actions run. No build or test was run locally.

Run [45265770690](https://github.com/centerionware/Clockrove/actions/runs/45265770690)
on `bacad38` passed localization checks, analysis, Flutter behavior tests,
Android builds, 759 native tests, and release checks. The combined API 24/35
journey and document-preview steps reported four root failures: the picker used
the Downloads shortcut instead of API 24's internal storage root, API 35 did
not index the shell-pushed fixture, the settings test could not find the
location row as a checkable node, and quote extraction included repeated page
headers inside a multi-page amount. Five viewer failures were downstream from
the quote test stopping before all style previews were generated. The follow-
up now uses internal storage when available, publishes the API 35 fixture
through MediaStore Downloads, closes the picker drawer before checking
navigation, falls back to the visible settings tile when semantics are split,
strips repeated quote headers from extracted text, and applies the formatter
correction. Exact-head Actions remains the acceptance gate. No local build or
test was run.

The exact PR head `ce58783` passed run [45275793641](https://github.com/centerionware/Clockrove/actions/runs/45275793641).
Merged-main run [45283121562](https://github.com/centerionware/Clockrove/actions/runs/45283121562)
on `e2a0d18` failed only the API 24 backup-picker journey. Its screenshot shows
DocumentsUI in the separate, empty Downloads provider while the fixture was
shell-pushed under `/sdcard/Download/Clockrove-CI-Backup`; the test had silently
treated that provider root as shared storage. The test now retries revealing
the internal storage root, rejects the Downloads-provider fallback, and always
opens the shared `Download/Clockrove-CI-Backup` path. Exact-main validation is
pending. No local build or test was run.

### November 24 widget copy follow-up

The owner reported widget copy such as "Due soon" staying in English regardless
of the language set in the app. Source history shows the widget and notification
copy already resolve through `localizedContext` from the saved language
(commit `a8f4304`, merged with PR #82), so a build before that commit still shows
the phone language. The remaining gap in that path was the task date: the widget
printed the stored ISO `due_date` unchanged in every language.

The task widget now formats a stored due date with the saved language's medium
date format and falls back to the stored value when it cannot be parsed. A
widget refresh also notifies the collection adapter, so the rows reload instead
of keeping the previous factory's strings after a language change. Two native
cases extend `LanguageResourcesTest`: the "Due soon" header and "today" copy
follow the saved override while the phone stays English, and due dates use the
chosen language's format. Exact-head Actions validation is pending; no local
build or test ran.
