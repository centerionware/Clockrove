#!/usr/bin/env bash
# Only invoked on the ephemeral Actions emulator after the original suite and pulls.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${GITHUB_WORKSPACE:?}"
clockrove_billing_api="${1:?API required}"
[[ "$clockrove_billing_api" == 24 || "$clockrove_billing_api" == 35 ]]
[[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == "$clockrove_billing_api" ]]
clockrove_billing_results="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_billing_api"
# Assert preceding evidence is already retained. Never reset a developer/owner phone.
test -s "$clockrove_billing_results/TEST-clockrove.xml"
test -s "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_billing_api/clockrove-invoice-modern.png"
adb -s emulator-5554 shell pm clear io.github.centerionware.clockrove | grep -F Success
mkdir -p "$clockrove_billing_results/billing-captures"
adb -s emulator-5554 logcat -v threadtime > "$clockrove_billing_results/billing-logcat.txt" &
clockrove_billing_logcat_pid=$!
trap 'kill "$clockrove_billing_logcat_pid" 2>/dev/null || true' EXIT
clockrove_billing_failed=false
# This class intentionally lives outside the preceding -e package filter so the
# released-schema migration runs in a new process with a fresh production singleton.
if ! timeout 240 adb -s emulator-5554 shell am instrument -w -r -e class io.github.centerionware.billingtest.InvoiceBillingDeviceTest -e listener io.github.centerionware.clockrove.CiXmlListener io.github.centerionware.clockrove.test/androidx.test.runner.AndroidJUnitRunner | tee "$clockrove_billing_results/billing-instrumentation.txt"; then
  clockrove_billing_failed=true
fi
kill "$clockrove_billing_logcat_pid" 2>/dev/null || true
wait "$clockrove_billing_logcat_pid" 2>/dev/null || true
trap - EXIT
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/billing-captures/. "$clockrove_billing_results/billing-captures/" || true
timeout 20 adb -s emulator-5554 shell dumpsys activity activities > "$clockrove_billing_results/billing-activities.txt" || true
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/clockrove-test-results.xml "$clockrove_billing_results/TEST-billing.xml"
[[ "$clockrove_billing_failed" == false ]]
python3 - "$clockrove_billing_results/TEST-billing.xml" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
assert int(root.attrib['tests']) == 1, root.attrib
assert all(int(root.attrib[k]) == 0 for k in ['failures', 'errors', 'skipped']), root.attrib
cases = root.findall('testcase')
assert len(cases) == 1
assert cases[0].attrib['classname'] == 'io.github.centerionware.billingtest.InvoiceBillingDeviceTest'
assert cases[0].attrib['name'] == 'realLegacyReviewQuantityTaxBillingExclusionAndRetainedPdfJourney'
print('Verified actual Android financial journey:', root.attrib)
PY_DEVICE
for clockrove_billing_capture in wide-money-board global-tax-settings job-tax-override legacy-selection quantity-editor quantity-board unbilled-tax-review issued-invoice already-billed retained-invoice; do
  test -s "$clockrove_billing_results/billing-captures/$clockrove_billing_capture.png"
done
