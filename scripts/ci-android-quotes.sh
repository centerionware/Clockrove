#!/usr/bin/env bash
# Fresh emulator app for the complete actual Flutter/native quote billing journey.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${GITHUB_WORKSPACE:?}"
clockrove_quote_api="${1:?API required}"
[[ "$clockrove_quote_api" == 24 || "$clockrove_quote_api" == 35 ]]
[[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == "$clockrove_quote_api" ]]
clockrove_quote_results="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_quote_api"
test -s "$clockrove_quote_results/TEST-payments.xml"
test -s "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_quote_api/clockrove-quote-modern.png"
adb -s emulator-5554 shell pm clear io.github.centerionware.clockrove | grep -F Success
mkdir -p "$clockrove_quote_results/quote-captures"
adb -s emulator-5554 logcat -v threadtime > "$clockrove_quote_results/quote-logcat.txt" &
clockrove_quote_logcat_pid=$!
trap 'kill "$clockrove_quote_logcat_pid" 2>/dev/null || true' EXIT
clockrove_quote_failed=false
if ! timeout --kill-after=10s 360 adb -s emulator-5554 shell am instrument -w -r -e class io.github.centerionware.quotetest.QuoteBillingDeviceTest -e listener io.github.centerionware.clockrove.CiXmlListener io.github.centerionware.clockrove.test/androidx.test.runner.AndroidJUnitRunner | tee "$clockrove_quote_results/quote-instrumentation.txt"; then
  clockrove_quote_failed=true
fi
kill "$clockrove_quote_logcat_pid" 2>/dev/null || true
wait "$clockrove_quote_logcat_pid" 2>/dev/null || true
trap - EXIT
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/quote-captures/. "$clockrove_quote_results/quote-captures/" || true
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/clockrove-test-results.xml "$clockrove_quote_results/TEST-quotes.xml"
[[ "$clockrove_quote_failed" == false ]]
python3 - "$clockrove_quote_results/TEST-quotes.xml" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
assert int(root.attrib['tests']) == 1, root.attrib
assert all(int(root.attrib[k]) == 0 for k in ['failures', 'errors', 'skipped']), root.attrib
cases = root.findall('testcase')
assert len(cases) == 1
assert cases[0].attrib['classname'] == 'io.github.centerionware.quotetest.QuoteBillingDeviceTest'
assert cases[0].attrib['name'] == 'actualQuoteIssueAcceptancePlanningCompletedQuantityInvoiceAndDuplicateExclusion'
print('Verified actual Android quote billing journey:', root.attrib)
PY_DEVICE
for clockrove_quote_capture in issued-quote accepted-quote planned-scope actual-task-quantity actual-source-selection actual-invoice-review actual-invoice-viewer related-invoices already-billed; do
  test -s "$clockrove_quote_results/quote-captures/$clockrove_quote_capture.png"
done
for clockrove_quote_style in minimal classic modern; do
  test -s "$clockrove_quote_results/quote-captures/actual-quote-invoice-$clockrove_quote_style.pdf"
  test -s "$clockrove_quote_results/quote-captures/actual-quote-invoice-$clockrove_quote_style.png"
  if [[ "$clockrove_quote_api" == 35 ]]; then
    test -s "$clockrove_quote_results/quote-captures/actual-quote-invoice-$clockrove_quote_style-extracted.txt"
  fi
done
