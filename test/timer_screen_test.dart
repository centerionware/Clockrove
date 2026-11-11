import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/l10n/app_strings.dart';
import 'package:clockrove/app_theme.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:clockrove/tracking/timer_screen.dart';
import 'package:clockrove/tracking/tracking_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class ScreenApi extends TimerApi {
  int version = 1;
  final jobs = [const JobSummary('job-a', 'Synthetic first')];
  RunningSession? running;
  final intents = <TimerAction>[];

  @override
  Stream<int> get committedVersions => const Stream<int>.empty();

  @override
  Future<TimerSnapshot> snapshot() async =>
      TimerSnapshot(version, List.unmodifiable(jobs), running);

  @override
  Future<String> createJob(String name) async {
    jobs.add(JobSummary('job-b', name));
    version++;
    return 'job-b';
  }

  @override
  Future<TimerOutcome> execute({
    required String id,
    required int version,
    required TimerAction action,
    String? jobId,
  }) async {
    if (version != this.version) {
      return TimerOutcome(this.version, 'STALE_STATE');
    }
    intents.add(action);
    running = action == TimerAction.stop
        ? null
        : RunningSession(
            'synthetic-session',
            jobId!,
            jobs.singleWhere((job) => job.id == jobId).name,
            DateTime.utc(2026, 11, 1),
          );
    this.version++;
    return TimerOutcome(this.version, null);
  }
}

class DeniedTrackingApi extends TrackingApi {
  int enableCalls = 0;
  @override
  Stream<String> get health => const Stream<String>.empty();
  @override
  Future<TrackingStatus> status() async =>
      const TrackingStatus('denied', 'STOPPED', true, false);
  @override
  Future<TrackingStatus> enable() async {
    enableCalls++;
    return status();
  }
}

class PreviewTrackingApi extends TrackingApi {
  @override
  Stream<String> get health => const Stream<String>.empty();
  @override
  Future<TrackingStatus> status() async => TrackingStatus(
    'approximate',
    'REQUESTING',
    true,
    true,
    lastObservationUtc: DateTime.now().toUtc().subtract(
      const Duration(minutes: 6),
    ),
    lastAccuracyMeters: 80,
  );
}

void main() {
  testWidgets('manual journey keeps New job last', (tester) async {
    final api = ScreenApi();
    final tracking = DeniedTrackingApi();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          timerApiProvider.overrideWithValue(api),
          trackingApiProvider.overrideWithValue(tracking),
        ],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: ClockroveTheme.light(),
          home: const TimerScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text(AppStrings.idle), findsOneWidget);
    await tester.scrollUntilVisible(find.text(AppStrings.clockIn), 200);
    await tester.pumpAndSettle();
    await tester.tap(find.text(AppStrings.clockIn));
    await tester.pumpAndSettle();
    expect(find.text(AppStrings.clockOut), findsOneWidget);
    await tester.scrollUntilVisible(find.text(AppStrings.enableLocation), 200);
    await tester.pumpAndSettle();
    await tester.tap(find.text(AppStrings.enableLocation));
    await tester.pumpAndSettle();
    expect(tracking.enableCalls, 0);
    expect(find.text(AppStrings.locationPurpose), findsOneWidget);
    await tester.tap(find.text(AppStrings.cancel));
    await tester.pumpAndSettle();
    expect(tracking.enableCalls, 0);
    await tester.tap(find.text(AppStrings.enableLocation));
    await tester.pumpAndSettle();
    await tester.tap(find.text(AppStrings.continueLabel));
    await tester.pumpAndSettle();
    expect(tracking.enableCalls, 1);
    await tester.scrollUntilVisible(find.text(AppStrings.clockOut), -200);
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<FilledButton>(
            find.widgetWithText(FilledButton, AppStrings.clockOut),
          )
          .onPressed,
      isNotNull,
    );
    await tester.scrollUntilVisible(find.text(AppStrings.newJob), 200);
    await tester.pumpAndSettle();
    await tester.tap(find.text(AppStrings.newJob));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Synthetic second');
    await tester.tap(find.text(AppStrings.create));
    await tester.pumpAndSettle();
    final tiles = tester.widgetList<ListTile>(find.byType(ListTile)).toList();
    expect((tiles.last.title! as Text).data, AppStrings.newJob);
    final second = find.widgetWithText(ListTile, 'Synthetic second');
    await tester.ensureVisible(second);
    await tester.tap(
      find.descendant(of: second, matching: find.byType(TextButton)),
    );
    await tester.pumpAndSettle();
    expect(api.running!.jobId, 'job-b');
    await tester.scrollUntilVisible(find.text(AppStrings.clockOut), -200);
    await tester.pumpAndSettle();
    await tester.tap(find.text(AppStrings.clockOut));
    await tester.pumpAndSettle();
    expect(find.text(AppStrings.idle), findsOneWidget);
    expect(api.intents, [
      TimerAction.start,
      TimerAction.switchJob,
      TimerAction.stop,
    ]);
  });
  for (final dark in [false, true]) {
    testWidgets('render clean timer preview ${dark ? "dark" : "light"}', (
      tester,
    ) async {
      await tester.runAsync(() async {
        final sdk = Platform.environment['FLUTTER_ROOT'];
        if (sdk == null) {
          throw StateError('FLUTTER_ROOT must identify the pinned preview SDK');
        }
        final loader = FontLoader('Roboto');
        for (final font in [
          'Roboto-Regular.ttf',
          'Roboto-Medium.ttf',
          'Roboto-Bold.ttf',
        ]) {
          loader.addFont(
            File('$sdk/bin/cache/artifacts/material_fonts/$font')
                .readAsBytes()
                .then(ByteData.sublistView),
          );
        }
        await loader.load();
        final icons = FontLoader('MaterialIcons');
        icons.addFont(
          File(
            '$sdk/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf',
          ).readAsBytes().then(ByteData.sublistView),
        );
        await icons.load();
      });
      tester.view.physicalSize = const Size(420, 920);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = ScreenApi();
      api.jobs.clear();
      api.jobs.addAll(const [
        JobSummary('studio', 'Studio renovation'),
        JobSummary('north', 'Northside fit-out'),
        JobSummary('admin', 'Planning and admin'),
      ]);
      api.running = RunningSession(
        'preview',
        'studio',
        'Studio renovation',
        DateTime.now().toUtc().subtract(
          const Duration(hours: 1, minutes: 23, seconds: 45),
        ),
      );
      final boundary = GlobalKey();
      await tester.pumpWidget(
        RepaintBoundary(
          key: boundary,
          child: ProviderScope(
            overrides: [
              timerApiProvider.overrideWithValue(api),
              trackingApiProvider.overrideWithValue(PreviewTrackingApi()),
            ],
            child: MaterialApp(
              debugShowCheckedModeBanner: false,
              theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
              home: const TimerScreen(),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.runAsync(() async {
        final render =
            boundary.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final image = await render.toImage(pixelRatio: 2);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        final directory = Directory('build/ui-preview')
          ..createSync(recursive: true);
        await File('${directory.path}/clockrove-${dark ? "dark" : "light"}.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
      await tester.scrollUntilVisible(find.text(AppStrings.locationTitle), 200);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text(AppStrings.disableLocation));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.runAsync(() async {
        final render =
            boundary.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final image = await render.toImage(pixelRatio: 2);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        await File(
          'build/ui-preview/clockrove-location-${dark ? "dark" : "light"}.png',
        ).writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }

  testWidgets('large text keeps the jobs list usable without overflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(400, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = ScreenApi();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          timerApiProvider.overrideWithValue(api),
          trackingApiProvider.overrideWithValue(DeniedTrackingApi()),
        ],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: ClockroveTheme.light(),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: const TextScaler.linear(1.8)),
            child: child!,
          ),
          home: const TimerScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text(AppStrings.newJob), 200);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text(AppStrings.newJob), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
