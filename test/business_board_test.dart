import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/business/business_command.dart';
import 'package:clockrove/business/job_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
}

class _BoardApi extends BusinessApi {
  final data = const JobBoard(
    1,
    'job',
    'Riverside renovation',
    'USD',
    2,
    false,
    [
      BoardColumn('todo', 'To do', false),
      BoardColumn('doing', 'Doing', false),
      BoardColumn('done', 'Done', true),
    ],
    [
      TaskCard(
        id: 'tiles',
        columnId: 'todo',
        title: 'Install kitchen tiles',
        description: 'Prepare the surface and lay the tiles.',
        position: 0,
        billable: true,
        currency: 'USD',
        priceMinor: '125000',
        dueDate: '2026-11-20',
      ),
      TaskCard(
        id: 'grout',
        columnId: 'todo',
        title: 'Choose grout finish',
        description: '',
        position: 1,
        billable: true,
        currency: 'USD',
      ),
    ],
  );
  @override
  Future<JobBoard> board(String jobId) async => data;
}

class _MovingTimer extends TimerApi {
  final versions = StreamController<int>.broadcast();
  @override
  Stream<int> get committedVersions => versions.stream;
}

class _MovingApi extends _BoardApi {
  int current = 1;
  int? submittedVersion;
  @override
  Future<JobBoard> board(String jobId) async => JobBoard(
    current,
    data.jobId,
    data.name,
    data.currency,
    data.digits,
    data.archived,
    data.columns,
    data.cards,
  );
  @override
  Future<TimerOutcome> moveCard(
    String id,
    JobBoard b,
    TaskCard c,
    String destination,
    int position,
  ) async {
    submittedVersion = b.version;
    return TimerOutcome(current, b.version == current ? null : 'STALE_STATE');
  }
}

Future<void> _show(
  WidgetTester tester, {
  bool dark = false,
  double scale = 1,
  Key? boundary,
}) async {
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundary,
      child: ProviderScope(
        overrides: [
          businessApiProvider.overrideWithValue(_BoardApi()),
          timerApiProvider.overrideWithValue(_Timer()),
        ],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
          home: MediaQuery(
            data: MediaQueryData.fromView(
              tester.view,
            ).copyWith(textScaler: TextScaler.linear(scale)),
            child: const JobScreen(
              jobId: 'job',
              jobName: 'Riverside renovation',
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Board waits for initial window metrics then renders its cards', (
    tester,
  ) async {
    final size = ValueNotifier<Size>(Size.zero);
    addTearDown(size.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          timerApiProvider.overrideWithValue(_Timer()),
          businessApiProvider.overrideWithValue(_BoardApi()),
        ],
        child: MaterialApp(
          home: ValueListenableBuilder<Size>(
            valueListenable: size,
            builder: (context, value, _) => MediaQuery(
              data: MediaQuery.of(context).copyWith(size: value),
              child: const JobScreen(jobId: 'job', jobName: 'Synthetic'),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('To do'), findsNothing);
    size.value = const Size(390, 844);
    await tester.pumpAndSettle();
    expect(find.text('To do'), findsOneWidget);
    expect(find.text('Install kitchen tiles'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('move dialog reflects native version refresh before confirming', (
    tester,
  ) async {
    final api = _MovingApi();
    final timer = _MovingTimer();
    addTearDown(timer.versions.close);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          businessApiProvider.overrideWithValue(api),
          timerApiProvider.overrideWithValue(timer),
        ],
        child: MaterialApp(
          theme: ClockroveTheme.light(),
          home: const JobScreen(jobId: 'job', jobName: 'Synthetic'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Task actions').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Move to Doing'));
    await tester.pumpAndSettle();
    api.current = 2;
    timer.versions.add(2);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Move task'));
    await tester.pumpAndSettle();
    expect(api.submittedVersion, 2);
    expect(find.byType(AlertDialog), findsNothing);
    expect(tester.takeException(), isNull);
  });

  test('money parsing preserves exact values, blank and explicit zero', () {
    expect(parseMoney('', 2), isNull);
    expect(parseMoney('0', 2), '0');
    expect(parseMoney('90071992547409.93', 2), '9007199254740993');
    expect(money('9007199254740993', 2), '90071992547409.93');
    expect(parseMoney('123', 0), '123');
    expect(money('1', 3), '0.001');
    for (final value in [
      '-1',
      'NaN',
      '1e2',
      '1,00',
      '1.001',
      '92233720368547758.08',
    ]) {
      expect(() => parseMoney(value, 2), throwsFormatException);
    }
  });
  testWidgets(
    'real board screen exposes pricing, due dates and accessible moves',
    (tester) async {
      await _show(tester);
      expect(find.text('Task board'), findsOneWidget);
      expect(find.text('USD 1250.00'), findsOneWidget);
      expect(find.text('Due 2026-11-20'), findsOneWidget);
      expect(find.text('Price not set'), findsOneWidget);
      await tester.tap(find.byTooltip('Task actions').first);
      await tester.pumpAndSettle();
      expect(find.text('Move down'), findsOneWidget);
      expect(find.text('Move to Doing'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'task editor preserves draft after validation and scrolls at large text',
    (tester) async {
      tester.view.physicalSize = const Size(420, 920);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await _show(tester, scale: 2);
      await tester.tap(find.text('Install kitchen tiles'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).at(2), '1.001');
      await tester.ensureVisible(find.text('Save task'));
      await tester.tap(find.text('Save task'));
      await tester.pumpAndSettle();
      expect(
        find.text('This currency allows 2 decimal places.'),
        findsOneWidget,
      );
      expect(find.text('Install kitchen tiles'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('unknown result retries the same closure and prevents leaving', (
    tester,
  ) async {
    var commands = 0;
    var attempts = 0;
    var committed = false;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: BusinessCommand(
            label: 'Save',
            create: () {
              commands++;
              return () async {
                attempts++;
                if (attempts == 1) throw StateError('synthetic response loss');
                return const TimerOutcome(2, null);
              };
            },
            onCommitted: () => committed = true,
          ),
        ),
      ),
    );
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Retry same operation'), findsOneWidget);
    expect(committed, isFalse);
    await tester.tap(find.text('Retry same operation'));
    await tester.pumpAndSettle();
    expect(commands, 1);
    expect(attempts, 2);
    expect(committed, isTrue);
  });
  for (final dark in [false, true]) {
    testWidgets('render task board ${dark ? 'dark' : 'light'}', (tester) async {
      await tester.runAsync(loadMapPreviewFonts);
      tester.view.physicalSize = const Size(420, 920);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final boundary = GlobalKey();
      await _show(tester, dark: dark, boundary: boundary);
      await tester.runAsync(() async {
        final image =
            await (boundary.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary)
                .toImage(pixelRatio: 2);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        Directory('build/ui-preview').createSync(recursive: true);
        await File(
          'build/ui-preview/clockrove-board-${dark ? 'dark' : 'light'}.png',
        ).writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
      expect(tester.takeException(), isNull);
    });
  }
}
