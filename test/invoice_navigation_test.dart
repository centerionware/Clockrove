import 'package:clockrove/business/invoice_screen.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Invoices extends ReportApi {
  @override
  Future<List<PreparedInvoice>> allInvoices() async => const [
    PreparedInvoice(
      'saved-invoice',
      'INV-1000',
      '107.50',
      'USD',
      'MODERN',
      jobId: 'removed-job',
      jobName: 'Synthetic removed job',
      customer: 'Synthetic customer',
      createdLabel: '2026-11-01T12:00:00.000Z',
      jobDeleted: true,
    ),
  ];
  @override
  Future<InvoicePage> preview(String id, int page) async => throw StateError(
    'Real PDF validation runs on Android; no mock document success',
  );
}

void main() {
  testWidgets(
    'main invoice list keeps removed-job documents accessible with customer and date',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [reportApiProvider.overrideWithValue(_Invoices())],
          child: const MaterialApp(home: InvoiceHistory()),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Invoices'), findsOneWidget);
      expect(
        find.textContaining('Synthetic removed job · job deleted'),
        findsOneWidget,
      );
      expect(find.textContaining('Synthetic customer'), findsOneWidget);
      expect(find.textContaining('USD 107.50'), findsOneWidget);
      expect(find.textContaining('Issued 2026-11-01'), findsOneWidget);
      await tester.tap(find.text('INV-1000'));
      await tester.pumpAndSettle();
      expect(find.byType(InvoiceScreen), findsOneWidget);
      expect(find.text('Retry PDF preview'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
