import 'package:clockrove/business/quote_api.dart';
import 'package:clockrove/business/quote_scope_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('clockrove/business/v1');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  test('mixed estimates keep every line in compatible exact-price groups', () {
    final wide = '9' * 1000;
    final draft = QuoteDraft(
      id: 'quote',
      title: 'Mixed estimate',
      currency: 'USD',
      taxUnits: 75000,
      lines: [
        const QuoteLine('tile', 'Tile', '', 'ITEM', '260000', '1250'),
        const QuoteLine(
          'preparation',
          'Preparation',
          '',
          'HOURS',
          '15000',
          '4000',
        ),
        const QuoteLine('trim', 'Trim', '', 'ITEM', '50000', '2000'),
        QuoteLine('special', 'Specialist', '', 'HOURS', '2500', wide),
        const QuoteLine('cleanup', 'Cleanup', '', 'HOURS', '5000', '4000'),
      ],
    );
    final groups = quoteScopeGroups(draft);
    expect(groups, hasLength(3));
    expect(groups[0].lines.map((line) => line.id), ['tile', 'trim']);
    expect(groups[0].mode, 'TASK');
    expect(groups[1].lines.map((line) => line.id), ['preparation', 'cleanup']);
    expect(groups[1].mode, 'HOURLY');
    expect(groups[2].rate, wide);
    expect(groups.expand((group) => group.lines).length, draft.lines.length);
    expect(() => groups[0].lines.clear(), throwsUnsupportedError);
  });
  test('scope command captures immutable identities and explicit review flags without sending money or worked hours', () async {
    final items = [const QuoteScopeItem('tile', 'task', 'Plan installation')];
    final draft = QuoteScopeDraft(
      quoteId: 'quote',
      revision: 2,
      jobId: 'job',
      items: items,
      note: 'Explicit review of current scope',
      historicalReviewed: true,
      jobDifferencesReviewed: true,
    );
    items.clear();
    expect(draft.items, hasLength(1));
    expect(() => draft.items.clear(), throwsUnsupportedError);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return {'version': 8, 'conflict': null};
        });
    expect(
      (await const QuoteScopeApi().apply('operation', 7, draft)).committed,
      isTrue,
    );
    final call = calls.single;
    expect(call.method, 'applyQuoteScope');
    final arguments = call.arguments as Map;
    expect(arguments['id'], 'operation');
    expect(arguments['version'], 7);
    final value = arguments['value'] as Map;
    expect(value['revision'], 2);
    expect(value['confirmed'], true);
    expect(value['historicalReviewed'], true);
    expect(value['jobDifferencesReviewed'], true);
    final item = (value['items'] as List).single as Map;
    expect(item, {
      'lineId': 'tile',
      'taskId': 'task',
      'title': 'Plan installation',
      'existing': false,
    });
    expect(value.containsKey('price'), isFalse);
    expect(value.containsKey('workedHours'), isFalse);
  });
}
