import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:clockrove/widgets/widget_configuration_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
  @override
  Future<TimerSnapshot> snapshot() async =>
      const TimerSnapshot(12, [JobSummary('job', 'Synthetic work')], null);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'Widget save retries the same command after a lost response and locks its choice',
    (tester) async {
      final calls = <Map<Object?, Object?>>[];
      var finished = false;
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(widgetChannel, (call) async {
        if (call.method == 'configuration') {
          return {'jobId': null, 'taskOnly': false};
        }
        if (call.method == 'finish') {
          finished = true;
          return null;
        }
        if (call.method == 'save') {
          calls.add(Map<Object?, Object?>.of(call.arguments as Map));
          if (calls.length == 1) throw PlatformException(code: 'LOST_REPLY');
          return {'version': 13, 'conflict': null};
        }
        return null;
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(widgetChannel, null),
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [timerApiProvider.overrideWithValue(_Timer())],
          child: const MaterialApp(home: WidgetConfigurationScreen()),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Synthetic work'));
      await tester.pump();
      await tester.tap(find.text('Save widget'));
      await tester.pumpAndSettle();
      expect(calls.single['jobId'], 'job');
      expect(calls.single['version'], 12);
      expect(find.text('Retry same operation'), findsOneWidget);
      expect(
        tester
            .widget<RadioListTile<String>>(
              find.byType(RadioListTile<String>).first,
            )
            .enabled,
        false,
      );
      await tester.tap(find.text('Retry same operation'));
      await tester.pumpAndSettle();
      expect(calls[1], equals(calls[0]));
      expect(finished, true);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'Unavailable default requires a deliberate replacement before saving',
    (tester) async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(
        widgetChannel,
        (call) async => {'jobId': 'archived', 'taskOnly': true},
      );
      addTearDown(
        () => messenger.setMockMethodCallHandler(widgetChannel, null),
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [timerApiProvider.overrideWithValue(_Timer())],
          child: const MaterialApp(home: WidgetConfigurationScreen()),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.textContaining('selected job is unavailable'),
        findsOneWidget,
      );
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Save widget'),
            )
            .onPressed,
        isNull,
      );
      await tester.tap(find.text('All active jobs'));
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Save widget'),
            )
            .onPressed,
        isNotNull,
      );
    },
  );
}
