import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:clockrove/business/quote_api.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('clockrove/business/v1');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  test('item quantities and estimated hours share exact line and subtotal tax boundaries', () {
    final value = quoteTotals(const [
      QuoteLine('tile', 'Tile', '', 'ITEM', '260000', '1250'),
      QuoteLine('hours', 'Preparation', '', 'HOURS', '15000', '4000'),
    ], 75000);
    expect(value.subtotal, '38500');
    expect(value.tax, '2888');
    expect(value.total, '41388');
    expect(
      quoteTotals(const [
        QuoteLine('tiny', 'Tiny', '', 'ITEM', '4999', '1'),
      ], 0).total,
      '0',
    );
    expect(
      quoteTotals(const [
        QuoteLine('half', 'Half', '', 'ITEM', '5000', '1'),
      ], 0).total,
      '1',
    );
    final huge = '9' * 1000;
    final exact = quoteTotals([
      QuoteLine('huge', 'Huge', '', 'HOURS', '260000', huge),
    ], 1000000);
    expect(exact.total, (BigInt.parse(huge) * BigInt.from(52)).toString());
  });

  test(
    'malformed or unsupported amounts reject without silently rounding input',
    () {
      for (final price in ['1.1', '-1', '01', '1e3', '9' * 1025]) {
        expect(
          () => quoteTotals([
            QuoteLine('line', 'Title', '', 'ITEM', '10000', price),
          ], 0),
          throwsFormatException,
        );
      }
      for (final quantity in ['0', '01', '1.5', '9223372036854775808']) {
        expect(
          () => quoteTotals([
            QuoteLine('line', 'Title', '', 'ITEM', quantity, '1'),
          ], 0),
          throwsFormatException,
        );
      }
      expect(() => quoteTotals(const [], 0), throwsFormatException);
      expect(
        () => quoteTotals(const [
          QuoteLine('line', 'Title', '', 'ITEM', '10000', '1'),
        ], 1000001),
        throwsFormatException,
      );
    },
  );

  test(
    'saved command captures immutable line definitions and sends exact strings',
    () async {
      final source = [
        QuoteLine('tile', 'Tile', '', 'ITEM', '260000', '9' * 1000),
      ];
      final draft = QuoteDraft(
        id: 'quote',
        title: 'Estimate',
        currency: 'USD',
        taxUnits: 75000,
        lines: source,
      );
      source.clear();
      expect(draft.lines, hasLength(1));
      expect(() => draft.lines.clear(), throwsUnsupportedError);
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            return {'version': 8, 'conflict': null};
          });
      final result = await const QuoteApi().save('command', 7, draft);
      expect(result.committed, isTrue);
      final call = calls.single;
      expect(call.method, 'saveQuote');
      final arguments = call.arguments as Map;
      expect(arguments['id'], 'command');
      expect(arguments['version'], 7);
      final value = arguments['value'] as Map,
          line = (value['lines'] as List).single as Map;
      expect(line['unitPriceMinor'], '9' * 1000);
      expect(line['quantityUnits'], '260000');
      expect(value['taxUnits'], 75000);
      expect(value['customerId'], isNull);
    },
  );

  test('frozen issued payload decodes strings and explicit revision decision separately', () {
    final data = QuoteData.decode({
      'version': 7,
      'draftJson': null,
      'latest': {
        'quoteId': 'quote',
        'revision': 2,
        'number': 'Q-1000',
        'created': 123,
        'decision': 'ACCEPTED',
        'decisionNote': 'Confirmed locally',
        'payload': jsonEncode({
          'draft': {
            'id': 'quote',
            'title': 'Estimate',
            'currency': 'USD',
            'taxUnits': 75000,
            'customerId': null,
            'jobId': null,
            'validUntil': '2026-12-01',
            'terms': '',
            'notes': '',
            'lines': [
              {
                'id': 'tile',
                'title': 'Tile',
                'detail': '',
                'kind': 'ITEM',
                'quantity': '260000',
                'unitPrice': '1250',
              },
            ],
          },
          'total': '349.38',
          'subtotal': '325.00',
          'tax': '24.38',
          'customer': 'Synthetic customer',
          'digits': 2,
        }),
      },
    });
    expect(data.draft, isNull);
    expect(data.latest!.revision, 2);
    expect(data.latest!.decision, 'ACCEPTED');
    expect(data.latest!.draft.lines.single.unitPrice, '1250');
    expect(data.latest!.total, '349.38');
  });
}
