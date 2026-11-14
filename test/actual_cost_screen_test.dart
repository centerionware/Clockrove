import 'package:clockrove/business/actual_cost_screen.dart';
import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/business/settings_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _CostsApi extends BusinessApi {
  int version = 1;
  final entries = <ActualCostEntry>[];
  ActualCostEntry? saved;

  @override
  Future<ActualCostsSnapshot> actualCosts({
    ActualCostCursor? before,
    int limit = 100,
  }) async => ActualCostsSnapshot(version, List.of(entries), null);

  @override
  Future<int> currencyDigits(String currency) async => switch (currency) {
    'JPY' => 0,
    'KWD' => 3,
    'USD' || 'EUR' || 'GBP' || 'CAD' || 'AUD' || 'NZD' || 'CHF' => 2,
    _ => throw const FormatException('Unsupported currency'),
  };

  @override
  Future<TimerOutcome> saveActualCost(
    String commandId,
    int expected,
    ActualCostEntry entry,
  ) async {
    saved = entry;
    entries.removeWhere((item) => item.id == entry.id);
    entries.insert(0, entry);
    version++;
    return TimerOutcome(version, null);
  }

  @override
  Future<TimerOutcome> removeActualCost(
    String commandId,
    int expected,
    String costId,
  ) async {
    entries.removeWhere((item) => item.id == costId);
    version++;
    return TimerOutcome(version, null);
  }
}

class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
}

class _Report extends ReportApi {
  @override
  Future<ReportContext> context() async => const ReportContext(
    'America/Los_Angeles',
    [ReportJob('job-one', 'Kitchen remodel', false)],
  );
}

class _Settings extends BusinessSettingsApi {
  static const value = CompanySettings(
    currency: 'USD',
    digits: 2,
    defaultHourlyMinor: null,
    companyName: 'Synthetic Company',
    companyEmail: '',
    companyPhone: '',
    companyAddress: '',
    companyWebsite: '',
    invoiceHeader: '',
    invoiceFooter: '',
    invoiceStyle: 'MODERN',
    emailSubject: '',
    emailBody: '',
  );
  @override
  Future<BusinessSettings> read() async => const BusinessSettings(1, value, []);
}

Future<void> _show(WidgetTester tester, _CostsApi api) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        businessApiProvider.overrideWithValue(api),
        reportApiProvider.overrideWithValue(_Report()),
        businessSettingsApiProvider.overrideWithValue(_Settings()),
        timerApiProvider.overrideWithValue(_Timer()),
      ],
      child: const MaterialApp(home: ActualCostScreen()),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'creates a cost with exact decimal amount and returns to ledger',
    (tester) async {
      final api = _CostsApi();
      await _show(tester, api);
      await tester.tap(find.text('Add cost'));
      await tester.pumpAndSettle();
      expect(find.text('Add actual cost'), findsOneWidget);
      await tester.enterText(find.byType(TextFormField).first, '12.34');
      await tester.enterText(find.byType(TextFormField).at(1), 'usd');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Save cost'));
      await tester.pumpAndSettle();
      expect(api.saved?.amountMinor, '1234');
      expect(api.saved?.currency, 'USD');
      expect(api.saved?.timezone, 'America/Los_Angeles');
      expect(find.text('Actual costs'), findsOneWidget);
      expect(find.text('USD 12.34'), findsOneWidget);
    },
  );

  testWidgets('rejects zero and excess currency precision before save', (
    tester,
  ) async {
    final api = _CostsApi();
    await _show(tester, api);
    await tester.tap(find.text('Add cost'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, '0');
    await tester.enterText(find.byType(TextFormField).at(1), 'USD');
    await tester.tap(find.text('Save cost'));
    await tester.pumpAndSettle();
    expect(api.saved, isNull);
    expect(find.text('Enter an amount greater than zero.'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField).first, '1.001');
    await tester.tap(find.text('Save cost'));
    await tester.pumpAndSettle();
    expect(api.saved, isNull);
    expect(find.text('This currency allows 2 decimal places.'), findsOneWidget);
  });
}
