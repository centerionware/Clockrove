import 'package:clockrove/business/legacy_invoice_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'review intent freezes selected sources and exact times before retry',
    () async {
      final tasks = ['task'];
      final times = [
        const LegacyCoveredTime(
          start: 1793491200000,
          end: 1793494800000,
          timezone: 'UTC',
        ),
      ];
      final draft = LegacyInvoiceDraft(
        invoiceId: 'legacy',
        tasks: tasks,
        times: times,
        note: 'Checked against the original invoice.',
        confirmed: true,
        noSources: false,
      );
      tasks.add('later-task');
      times.clear();
      expect(draft.tasks, ['task']);
      expect(draft.times.single.end, 1793494800000);
      final calls = <MethodCall>[];
      const channel = MethodChannel('clockrove/business/v1');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {'version': 8, 'conflict': null};
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      const api = LegacyInvoiceApi();
      await api.reconcile('same', 7, draft);
      await api.reconcile('same', 7, draft);
      expect(
        calls.map((call) => call.method),
        everyElement('reconcileInvoiceCoverage'),
      );
      expect(calls[0].arguments, calls[1].arguments);
      final value = (calls.first.arguments as Map)['value'] as Map;
      expect(value['taskIds'], ['task']);
      expect((value['spans'] as List).single, {
        'start': 1793491200000,
        'end': 1793494800000,
        'timezone': 'UTC',
      });
      expect(value['confirmed'], isTrue);
      expect(value['noSources'], isFalse);
    },
  );
}
