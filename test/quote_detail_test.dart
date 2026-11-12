import 'dart:convert';

import 'package:clockrove/business/quote_api.dart';
import 'package:clockrove/business/quote_detail_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

QuoteDraft _draft() => QuoteDraft(
  id: 'quote',
  title: 'Synthetic tile estimate',
  currency: 'USD',
  taxUnits: 75000,
  lines: const [
    QuoteLine('tile', 'Install tile', '', 'ITEM', '260000', '1250'),
  ],
);
QuoteRevision _issued({String? decision, String? note}) => QuoteRevision({
  'quoteId': 'quote',
  'revision': 1,
  'number': 'Q-1000',
  'created': 1793494800000,
  'decision': decision,
  'decisionNote': note,
  'payload': jsonEncode({
    'draft': {
      'id': 'quote',
      'title': 'Synthetic tile estimate',
      'currency': 'USD',
      'taxUnits': 75000,
      'customerId': null,
      'jobId': null,
      'validUntil': null,
      'terms': '',
      'notes': '',
      'lines': [
        {
          'id': 'tile',
          'title': 'Install tile',
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
});

class _Quotes extends QuoteApi {
  QuoteData current = QuoteData(
    1,
    _draft(),
    null,
    draftDigits: 2,
    today: '2026-11-01',
    timezone: 'UTC',
  );
  VoidCallback changed = () {};
  final issues = <({String id, int version, String quote})>[];
  final decisions =
      <
        ({String id, int version, int revision, String decision, String note})
      >[];
  @override
  Future<List<QuoteHistoryEntry>> history(String id, {int? before}) async => [];
  @override
  Future<TimerOutcome> issue(String id, int version, String quoteId) async {
    issues.add((id: id, version: version, quote: quoteId));
    current = QuoteData(
      2,
      null,
      _issued(),
      today: '2026-11-01',
      timezone: 'UTC',
    );
    changed();
    await Future<void>.delayed(Duration.zero);
    if (issues.length == 1) {
      throw StateError('Synthetic committed response lost');
    }
    return const TimerOutcome(2, null);
  }

  @override
  Future<TimerOutcome> decide(
    String id,
    int version,
    String quoteId,
    int revision,
    String decision,
    String note,
  ) async {
    decisions.add((
      id: id,
      version: version,
      revision: revision,
      decision: decision,
      note: note,
    ));
    current = QuoteData(
      3,
      null,
      _issued(decision: decision, note: note),
      today: '2026-11-01',
      timezone: 'UTC',
    );
    changed();
    return const TimerOutcome(3, null);
  }
}

Future<void> _show(WidgetTester tester, _Quotes api) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        quoteApiProvider.overrideWithValue(api),
        quoteProvider('quote').overrideWith((ref) async => api.current),
      ],
      child: const MaterialApp(home: QuoteDetailScreen(quoteId: 'quote')),
    ),
  );
  await tester.pumpAndSettle();
  final container = ProviderScope.containerOf(
    tester.element(find.byType(QuoteDetailScreen)),
  );
  api.changed = () => container.invalidate(quoteProvider('quote'));
}

Future<void> _tap(WidgetTester tester, String text) async {
  final finder = find.text(text);
  if (finder.evaluate().isEmpty) {
    await tester.scrollUntilVisible(
      finder,
      300,
      scrollable: find.byType(Scrollable).first,
    );
  } else {
    await tester.ensureVisible(finder);
  }
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'cancelled issue review writes nothing and unknown reply retains its captured command after version event',
    (tester) async {
      final api = _Quotes();
      await _show(tester, api);
      await _tap(tester, 'Review quote for issue');
      expect(find.text('Estimated total USD 349.38'), findsOneWidget);
      await _tap(tester, 'Keep editing');
      expect(api.issues, isEmpty);
      await _tap(tester, 'Review quote for issue');
      await _tap(tester, 'Confirm review');
      await _tap(tester, 'Issue quote');
      expect(api.issues, hasLength(1));
      expect(find.text('Retry same operation'), findsOneWidget);
      expect(find.text('Q-1000'), findsOneWidget);
      await _tap(tester, 'Retry same operation');
      expect(api.issues, hasLength(2));
      expect(api.issues[1], api.issues[0]);
      expect(find.text('Retry same operation'), findsNothing);
      expect(find.text('Open issued quote'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'local acceptance needs explicit reviewed revision and note, while cancelling changes nothing',
    (tester) async {
      final api = _Quotes()
        ..current = QuoteData(
          2,
          null,
          _issued(),
          today: '2026-11-01',
          timezone: 'UTC',
        );
      await _show(tester, api);
      final note = find.widgetWithText(TextField, 'Private decision note');
      await tester.scrollUntilVisible(
        note,
        400,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      await tester.enterText(
        note,
        'Customer agreed by phone on the recorded date.',
      );
      await _tap(tester, 'Review customer decision');
      await _tap(tester, 'Cancel');
      expect(api.decisions, isEmpty);
      await _tap(tester, 'Review customer decision');
      await _tap(tester, 'Confirm review');
      expect(api.decisions, isEmpty);
      await _tap(tester, 'Record reviewed decision');
      expect(api.decisions, hasLength(1));
      expect(api.decisions.single.version, 2);
      expect(api.decisions.single.revision, 1);
      expect(api.decisions.single.decision, 'ACCEPTED');
      expect(
        api.decisions.single.note,
        'Customer agreed by phone on the recorded date.',
      );
      expect(find.text('Accepted locally'), findsWidgets);
      expect(find.text('Review customer decision'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
