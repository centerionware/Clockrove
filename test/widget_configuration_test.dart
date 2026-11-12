import 'dart:async';

import 'package:clockrove/main.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:clockrove/widgets/widget_configuration_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Timer extends TimerApi {
  _Timer([this.events = const Stream.empty()]);
  final Stream<int> events;
  int version = 12, reads = 0;
  Completer<TimerSnapshot>? heldRead;
  @override
  Stream<int> get committedVersions => events;
  TimerSnapshot get value =>
      TimerSnapshot(version, const [JobSummary('job', 'Synthetic work')], null);
  @override
  Future<TimerSnapshot> snapshot() async {
    reads++;
    return heldRead == null ? value : heldRead!.future;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final readFails in [false, true]) {
    testWidgets(
      'Committed version before save response retains widget setup (refresh error: $readFails)',
      (tester) async {
        final events = StreamController<int>();
        addTearDown(events.close);
        final timer = _Timer(events.stream);
        final reply = Completer<Map<String, Object?>>();
        final reload = Completer<TimerSnapshot>();
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
            timer.version = 13;
            timer.heldRead = reload;
            events.add(13);
            return reply.future;
          }
          return null;
        });
        addTearDown(
          () => messenger.setMockMethodCallHandler(widgetChannel, null),
        );
        await tester.pumpWidget(
          ProviderScope(
            overrides: [timerApiProvider.overrideWithValue(timer)],
            child: const MaterialApp(home: WidgetConfigurationScreen()),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.text('Synthetic work'));
        await tester.pump();
        await tester.tap(find.text('Save widget'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 50));
        expect(timer.reads, greaterThanOrEqualTo(2));
        expect(find.byType(RadioListTile<String>), findsNWidgets(2));
        expect(
          tester
              .widget<RadioListTile<String>>(
                find.byType(RadioListTile<String>).last,
              )
              .enabled,
          false,
        );
        if (readFails) {
          reload.completeError(StateError('Synthetic refresh unavailable'));
        } else {
          reload.complete(timer.value);
        }
        timer.heldRead = null;
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 50));
        expect(find.byType(RadioListTile<String>), findsNWidgets(2));
        reply.complete({'version': 13, 'conflict': null});
        await tester.pumpAndSettle();
        expect(calls, hasLength(1));
        expect(calls.single['version'], 12);
        expect(calls.single['jobId'], 'job');
        expect(finished, true);
        expect(find.text('Done'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }
  testWidgets(
    'Full app starts directly in widget setup with one initial route',
    (tester) async {
      tester.binding.platformDispatcher.defaultRouteNameTestValue =
          '/widget-configuration';
      addTearDown(
        tester.binding.platformDispatcher.clearDefaultRouteNameTestValue,
      );
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(
        widgetChannel,
        (call) async => {'jobId': null, 'taskOnly': false},
      );
      addTearDown(
        () => messenger.setMockMethodCallHandler(widgetChannel, null),
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [timerApiProvider.overrideWithValue(_Timer())],
          child: const ClockroveApp(),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(WidgetConfigurationScreen), findsOneWidget);
      expect(find.text('Synthetic work'), findsOneWidget);
      expect(tester.takeException(), isNull);
      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      expect(navigator.canPop(), false);
    },
  );
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
