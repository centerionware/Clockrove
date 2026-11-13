import 'dart:convert';

import 'package:clockrove/business/quote_scope_api.dart';
import 'package:clockrove/business/quote_scope_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

QuoteScopeData _data({int version = 7}) => QuoteScopeData({
  'version': version,
  'issued': {
    'quoteId': 'quote',
    'revision': 1,
    'number': 'Q-1000',
    'created': 1793494800000,
    'decision': 'ACCEPTED',
    'decisionNote': 'Explicit synthetic local acceptance',
    'payload': jsonEncode({
      'draft': {
        'id': 'quote',
        'title': 'Tile installation',
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
  },
  'latestRevision': 1,
  'linkedLines': <String>[],
  'bindings': <Object?>[],
  'jobs': <Object?>[],
  'target': null,
  'tasks': <Object?>[],
});

class _Scope extends QuoteScopeApi {
  bool failRead = false;
  final calls = <({String id, int version, QuoteScopeDraft draft})>[];
  @override
  Future<QuoteScopeData> read(
    String quoteId,
    int revision, {
    String? jobId,
  }) async {
    if (failRead) {
      throw StateError('Synthetic read failure');
    }
    return _data(version: calls.isEmpty ? 7 : 8);
  }

  @override
  Future<TimerOutcome> apply(
    String id,
    int version,
    QuoteScopeDraft draft,
  ) async {
    calls.add((id: id, version: version, draft: draft));
    if (calls.length == 1) {
      throw StateError('Synthetic committed response lost');
    }
    return const TimerOutcome(8, null);
  }
}

Future<void> _show(WidgetTester tester, _Scope api) async {
  tester.view.physicalSize = const Size(1000, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [quoteScopeApiProvider.overrideWithValue(api)],
      child: const MaterialApp(
        home: QuoteScopeScreen(quoteId: 'quote', revision: 1),
      ),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text('Review group plan'));
  await tester.pumpAndSettle();
  await tester.enterText(
    find.widgetWithText(TextFormField, 'Private scope review note'),
    'Reviewed exact installation scope',
  );
}

void main() {
  testWidgets(
    'scope review can be cancelled and lost outcome retries identical reviewed command',
    (tester) async {
      final api = _Scope();
      await _show(tester, api);
      await tester.ensureVisible(find.text('Review selected plan'));
      await tester.tap(find.text('Review selected plan'));
      await tester.pumpAndSettle();
      expect(find.text('Apply this reviewed plan?'), findsOneWidget);
      expect(api.calls, isEmpty);
      await tester.tap(find.text('Keep editing'));
      await tester.pumpAndSettle();
      expect(api.calls, isEmpty);
      await tester.tap(find.text('Review selected plan'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm review'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Apply reviewed plan'));
      await tester.tap(find.text('Apply reviewed plan'));
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(1));
      final first = api.calls.single;
      expect(first.version, 7);
      expect(first.draft.items.single.lineId, 'tile');
      expect(first.draft.newJobName, 'Tile installation · tasks');
      expect(first.draft.items.single.existing, isFalse);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('Review scope plan'), findsOneWidget);
      await tester.tap(find.text('Retry same operation'));
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(2));
      expect(api.calls.last.id, first.id);
      expect(api.calls.last.version, first.version);
      expect(identical(api.calls.last.draft, first.draft), isTrue);
      expect(find.text('Plan accepted scope'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'failed scope refresh retains note and blocks review until successful read',
    (tester) async {
      final api = _Scope();
      await _show(tester, api);
      api.failRead = true;
      await tester.tap(find.byTooltip('Refresh scope review data'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Review selected plan'));
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, 'Review selected plan'),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<TextFormField>(
              find.widgetWithText(TextFormField, 'Private scope review note'),
            )
            .controller!
            .text,
        'Reviewed exact installation scope',
      );
      expect(api.calls, isEmpty);
      api.failRead = false;
      await tester.tap(find.byTooltip('Refresh scope review data'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Review selected plan'));
      await tester.tap(find.text('Review selected plan'));
      await tester.pumpAndSettle();
      expect(find.text('Apply this reviewed plan?'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
