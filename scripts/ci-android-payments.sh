#!/usr/bin/env bash
# Runs the actual Flutter/native payment journey in a fresh Actions-only app process.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${GITHUB_WORKSPACE:?}"
clockrove_payment_api="${1:?API required}"
[[ "$clockrove_payment_api" == 24 || "$clockrove_payment_api" == 35 ]]
[[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == "$clockrove_payment_api" ]]
clockrove_payment_results="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_payment_api"
test -s "$clockrove_payment_results/TEST-copies.xml"
test -s "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_payment_api/clockrove-receipt-paid-modern.png"
adb -s emulator-5554 shell pm clear io.github.centerionware.clockrove | grep -F Success
mkdir -p "$clockrove_payment_results/payment-captures"
adb -s emulator-5554 logcat -v threadtime > "$clockrove_payment_results/payment-logcat.txt" &
clockrove_payment_logcat_pid=$!
trap 'kill "$clockrove_payment_logcat_pid" 2>/dev/null || true' EXIT
clockrove_payment_failed=false
if ! timeout 300 adb -s emulator-5554 shell am instrument -w -r -e class io.github.centerionware.billingtest.InvoicePaymentDeviceTest -e listener io.github.centerionware.clockrove.CiXmlListener io.github.centerionware.clockrove.test/androidx.test.runner.AndroidJUnitRunner | tee "$clockrove_payment_results/payment-instrumentation.txt"; then
  clockrove_payment_failed=true
fi
kill "$clockrove_payment_logcat_pid" 2>/dev/null || true
wait "$clockrove_payment_logcat_pid" 2>/dev/null || true
trap - EXIT
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/payment-captures/. "$clockrove_payment_results/payment-captures/" || true
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/clockrove-test-results.xml "$clockrove_payment_results/TEST-payments.xml"
[[ "$clockrove_payment_failed" == false ]]
python3 - "$clockrove_payment_results/TEST-payments.xml" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
assert int(root.attrib['tests']) == 1, root.attrib
assert all(int(root.attrib[k]) == 0 for k in ['failures', 'errors', 'skipped']), root.attrib
cases = root.findall('testcase')
assert len(cases) == 1
assert cases[0].attrib['classname'] == 'io.github.centerionware.billingtest.InvoicePaymentDeviceTest'
assert cases[0].attrib['name'] == 'actualSettingsPartialPaymentSettlementCorrectionAndDeletedJobReceipt'
print('Verified actual Android payment journey:', root.attrib)
PY_DEVICE
for clockrove_payment_capture in receivables-settings unpaid invalid-payment partial-receipt partial-balance paid-receipt correction-receipt retained-balance retained-paid-receipt replacement-review replacement-balance replacement-original-receipt void-balance cancellation-copy unpaid-void; do
  test -s "$clockrove_payment_results/payment-captures/$clockrove_payment_capture.png"
done
