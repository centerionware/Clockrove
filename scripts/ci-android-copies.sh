#!/usr/bin/env bash
# Financial document lifecycle is isolated from the settings first-invoice regression.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${GITHUB_WORKSPACE:?}"
clockrove_copy_api="${1:?API required}"
[[ "$clockrove_copy_api" == 24 || "$clockrove_copy_api" == 35 ]]
[[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == "$clockrove_copy_api" ]]
clockrove_copy_results="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_copy_api"
clockrove_copy_previews="$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_copy_api"
# Preceding synthetic results must be retained before clearing this Actions emulator.
test -s "$clockrove_copy_results/TEST-clockrove.xml"
test -s "$clockrove_copy_results/TEST-billing.xml"
test -s "$clockrove_copy_results/billing-captures/retained-invoice.png"
adb -s emulator-5554 shell pm clear io.github.centerionware.clockrove | grep -F Success
adb -s emulator-5554 logcat -v threadtime > "$clockrove_copy_results/copies-logcat.txt" &
clockrove_copy_logcat_pid=$!
trap 'kill "$clockrove_copy_logcat_pid" 2>/dev/null || true' EXIT
clockrove_copy_failed=false
if ! timeout 300 adb -s emulator-5554 shell am instrument -w -r -e class io.github.centerionware.billingtest.InvoiceCopiesPdfTest,io.github.centerionware.billingtest.InvoiceBlobStorageDeviceTest,io.github.centerionware.billingtest.QuotePdfDeviceTest -e listener io.github.centerionware.clockrove.CiXmlListener io.github.centerionware.clockrove.test/androidx.test.runner.AndroidJUnitRunner | tee "$clockrove_copy_results/copies-instrumentation.txt"; then
  clockrove_copy_failed=true
fi
kill "$clockrove_copy_logcat_pid" 2>/dev/null || true
wait "$clockrove_copy_logcat_pid" 2>/dev/null || true
trap - EXIT
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/invoice-previews/. "$clockrove_copy_previews/" || true
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/clockrove-test-results.xml "$clockrove_copy_results/TEST-copies.xml"
python3 - "$clockrove_copy_results/TEST-copies.xml" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
for case in root.findall('testcase'):
    failure = case.find('failure')
    if failure is None:
        continue
    detail = ' '.join(part for part in [failure.get('message', ''), (failure.text or '').strip()] if part)
    detail = detail[:3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f"::error title=Android PDF acceptance::{case.attrib.get('name', 'unknown test')}: {detail}")
assert int(root.attrib['tests']) == 5, root.attrib
assert all(int(root.attrib[k]) == 0 for k in ['failures', 'errors', 'skipped']), root.attrib
cases = root.findall('testcase')
assert len(cases) == 5
assert {(c.attrib['classname'], c.attrib['name']) for c in cases} == {
    ('io.github.centerionware.billingtest.InvoiceCopiesPdfTest', 'settingsStylePreviewRendersSamplePdfWithoutChangingBusinessState'),
    ('io.github.centerionware.billingtest.InvoiceCopiesPdfTest', 'invoicePartiesUseWrappedColumnsAndSubtotalTaxNumbersEndAtTheRightEdge'),
    ('io.github.centerionware.billingtest.InvoiceCopiesPdfTest', 'nativePaymentsProduceAllStylePartialPaidBalanceCorrectionAndWideCopies'),
    ('io.github.centerionware.billingtest.InvoiceBlobStorageDeviceTest', 'originalCopyAndLogoBytesSurviveActualCursorWindowLimit'),
    ('io.github.centerionware.billingtest.QuotePdfDeviceTest', 'allStylesPreserveIssuedRevisionsAndExactWideEstimatesWithoutBillingWork'),
}
print('Verified actual Android immutable financial copies:', root.attrib)
PY_DEVICE
[[ "$clockrove_copy_failed" == false ]]
for clockrove_copy_style in minimal classic modern; do
  test -s "$clockrove_copy_previews/clockrove-settings-preview-$clockrove_copy_style.png"
  test -s "$clockrove_copy_previews/clockrove-party-layout-$clockrove_copy_style.png"
  for clockrove_copy_kind in receipt-partial receipt-paid balance-paid payment-correction receipt-wide void replacement replacement-paid quote quote-revision quote-wide; do
    test -s "$clockrove_copy_previews/clockrove-$clockrove_copy_kind-$clockrove_copy_style.pdf"
    test -s "$clockrove_copy_previews/clockrove-$clockrove_copy_kind-$clockrove_copy_style.png"
  done
done
