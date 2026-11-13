import 'package:clockrove/business/quote_invoice_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('clockrove/business/v1');
  const request = QuoteInvoiceRequest(
    quoteId: 'quote',
    revision: 2,
    jobId: 'job',
    firstDate: '2026-11-01',
    lastDate: '2026-11-12',
    timezone: 'UTC',
  );
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  test(
    'actual-work choices freeze sources and preserve native wide money strings',
    () async {
      final ids = ['task'];
      final times = <QuoteInvoiceTime>[];
      final choices = QuoteInvoiceChoices(
        taskIds: ids,
        times: times,
        note: 'Explicit actual work review',
        historicalReviewed: true,
      );
      ids.clear();
      times.add(const QuoteInvoiceTime('not-selected', 1, 2));
      expect(choices.taskIds, ['task']);
      expect(choices.times, isEmpty);
      expect(() => choices.taskIds.clear(), throwsUnsupportedError);
      final wide = '9' * 1000;
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            if (call.method == 'calculateQuoteInvoice') {
              return {
                'version': 7,
                'problem': null,
                'subtotalMinor': wide,
                'taxMinor': '0',
                'totalMinor': wide,
                'taxUnits': 0,
                'currency': 'USD',
                'digits': 2,
                'customer': 'Synthetic customer',
                'email': 'customer@example.test',
              };
            }
            return {'version': 8, 'conflict': null};
          });
      const api = QuoteInvoiceApi();
      final calculation = await api.calculate(request, choices);
      expect(calculation.total, wide);
      expect(calculation.subtotal, wide);
      expect(calculation.version, 7);
      expect(
        (await api.prepare(
          'operation',
          calculation.version,
          'invoice',
          request,
          choices,
        )).committed,
        isTrue,
      );
      final arguments = calls.last.arguments as Map;
      expect(arguments['id'], 'operation');
      expect(arguments['version'], 7);
      final value = arguments['value'] as Map;
      expect(value['invoiceId'], 'invoice');
      expect(value['request'], request.encode());
      final encoded = value['choices'] as Map;
      expect(encoded['taskIds'], ['task']);
      expect(encoded['times'], isEmpty);
      expect(encoded['confirmed'], true);
      expect(encoded['historicalReviewed'], true);
      expect(encoded['currentPrices'], false);
      expect(value.containsKey('totalMinor'), isFalse);
      expect(encoded.containsKey('unitPriceMinor'), isFalse);
    },
  );
  test('related history decodes metadata and returns exact keyset cursor without private payload', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'quoteInvoices');
          expect((call.arguments as Map)['before'], {
            'created': 100,
            'invoiceId': 'previous',
          });
          return {
            'invoices': [
              {
                'invoiceId': 'replacement',
                'number': 'INV-1001',
                'revision': 2,
                'originalInvoiceId': 'original',
                'created': 99,
              },
            ],
            'next': {'created': 99, 'invoiceId': 'replacement'},
          };
        });
    final result = await const QuoteInvoiceApi().invoices(
      'quote',
      before: const QuoteInvoiceCursor(100, 'previous'),
    );
    expect(result.invoices.single.originalId, 'original');
    expect(result.invoices.single.revision, 2);
    expect(result.next!.encode(), {'created': 99, 'invoiceId': 'replacement'});
    expect(() => result.invoices.clear(), throwsUnsupportedError);
  });
}
