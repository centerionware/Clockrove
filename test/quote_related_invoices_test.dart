import 'package:clockrove/business/quote_invoice_api.dart';
import 'package:clockrove/business/quote_related_invoices_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _History extends QuoteInvoiceApi {
  bool fail = false;
  final beforeCalls = <QuoteInvoiceCursor?>[];
  @override
  Future<QuoteRelatedInvoices> invoices(
    String quoteId, {
    QuoteInvoiceCursor? before,
    int limit = 20,
  }) async {
    beforeCalls.add(before);
    if (fail) {
      throw StateError('Synthetic history read failure');
    }
    return QuoteRelatedInvoices({
      'invoices': [
        {
          'invoiceId': before == null ? 'new' : 'old',
          'number': before == null ? 'INV-1001' : 'INV-1000',
          'revision': before == null ? 2 : 1,
          'originalInvoiceId': 'old',
          'created': before == null ? 200 : 100,
          'createdLabel': '2026-11-12 12:00:00 UTC',
        },
      ],
      'next': before == null ? {'created': 200, 'invoiceId': 'new'} : null,
    });
  }
}

void main() {
  testWidgets(
    'related history retains rows and retries the same failed page without resetting its cursor',
    (tester) async {
      final api = _History();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [quoteInvoiceApiProvider.overrideWithValue(api)],
          child: const MaterialApp(
            home: QuoteRelatedInvoicesScreen(quoteId: 'quote'),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('INV-1001'), findsOneWidget);
      api.fail = true;
      await tester.tap(find.text('Load older quote invoices'));
      await tester.pumpAndSettle();
      expect(find.text('INV-1001'), findsOneWidget);
      expect(api.beforeCalls.last!.invoiceId, 'new');
      api.fail = false;
      await tester.tap(find.text('Retry quote invoices'));
      await tester.pumpAndSettle();
      expect(api.beforeCalls.last!.invoiceId, 'new');
      expect(find.text('INV-1001'), findsOneWidget);
      expect(find.text('INV-1000'), findsOneWidget);
      expect(find.text('Load older quote invoices'), findsNothing);
      api.fail = true;
      await tester.tap(find.byTooltip('Refresh quote invoices'));
      await tester.pumpAndSettle();
      expect(api.beforeCalls.last, isNull);
      expect(find.text('INV-1000'), findsOneWidget);
      api.fail = false;
      await tester.tap(find.text('Retry quote invoices'));
      await tester.pumpAndSettle();
      expect(api.beforeCalls.last, isNull);
      expect(find.text('INV-1001'), findsOneWidget);
      expect(find.text('INV-1000'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
