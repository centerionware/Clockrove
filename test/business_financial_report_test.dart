import 'package:clockrove/business/business_financial_report_screen.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
}

class _Reports extends ReportApi {
  BusinessReportSelection? lastSelection;
  bool? savedPdf;

  @override
  Future<ReportContext> context() async => const ReportContext(
    'UTC',
    [ReportJob('job', 'Synthetic tile renovation', false)],
    [ReportCustomer('customer', 'Synthetic customer', false)],
  );

  @override
  Future<BusinessFinancialReport> businessReport(
    BusinessReportSelection selection,
  ) async {
    lastSelection = selection;
    return BusinessFinancialReport(
      version: 1,
      timezone: selection.timezone,
      firstDate: selection.firstDate,
      lastDate: selection.lastDate,
      asOfExclusiveMillis: 1,
      excludedUnknownCustomerCostCount: 0,
      summaries: const [
        BusinessReportSummary(
          currency: 'USD',
          digits: 2,
          invoicedSales: '15000',
          cashReceived: '5000',
          taxBilled: '1125',
          taxCollected: '349',
          outstanding: '11125',
          labor: '5000',
          supplies: '10000',
          unclassified: '0',
          directCosts: '2000',
          overhead: '1000',
          grossProfit: '13000',
          netProfit: '12000',
          grossMarginPercent: '86.6667',
          netMarginPercent: '80',
          cashSurplus: '2000',
          costCount: 2,
          unattributedCostCount: 1,
          legacyInvoiceCount: 0,
          unknownTaxInvoiceCount: 0,
        ),
      ],
      invoices: const [],
      cash: const [],
      costs: const [],
    );
  }

  @override
  Future<bool> saveBusinessReport(
    BusinessReportSelection selection,
    int version, {
    required bool pdf,
  }) async {
    lastSelection = selection;
    savedPdf = pdf;
    return true;
  }
}

void main() {
  testWidgets('financial report filters customer and exports selected view', (
    tester,
  ) async {
    final reports = _Reports();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          reportApiProvider.overrideWithValue(reports),
          timerApiProvider.overrideWithValue(_Timer()),
        ],
        child: const MaterialApp(
          home: BusinessFinancialReportScreen(initialJobIds: ['job']),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Business report'), findsOneWidget);
    expect(find.text('Invoiced sales · tax exclusive'), findsOneWidget);
    expect(find.text('USD 150.00'), findsOneWidget);

    await tester.tap(find.text('All customers'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Synthetic customer'));
    await tester.tap(find.text('Apply'));
    await tester.pumpAndSettle();
    expect(reports.lastSelection?.customers, ['customer']);

    await tester.scrollUntilVisible(
      find.text('Export CSV'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(
      find.text(
        'CSV amount columns contain exact minor-unit integers as text.',
      ),
      findsOneWidget,
    );
    await tester.tap(find.text('Export CSV'));
    await tester.pumpAndSettle();
    expect(reports.savedPdf, isFalse);
    expect(find.text('CSV saved.'), findsOneWidget);
  });
}
