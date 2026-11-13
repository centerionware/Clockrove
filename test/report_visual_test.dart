import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/business/report_map.dart';
import 'package:clockrove/business/report_screen.dart';
import 'package:clockrove/maps/work_location_map.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
}

class _VisualReport extends ReportApi {
  _VisualReport(this.taskMode);
  final bool taskMode;
  static final start = DateTime.utc(2026, 11, 11, 8).millisecondsSinceEpoch;
  static final points = [
    ReportPoint(
      'first',
      'session',
      'job',
      'Studio renovation',
      start,
      '2026-11-11T08:00:00.000Z',
      51.5,
      -.1,
      10,
    ),
    ReportPoint(
      'second',
      'session',
      'job',
      'Studio renovation',
      start + 600000,
      '2026-11-11T08:10:00.000Z',
      51.5005,
      -.1005,
      14,
    ),
  ];
  @override
  Future<ReportContext> context() async => const ReportContext('UTC', [
    ReportJob('job', 'Studio renovation', false),
  ]);
  @override
  Future<WorkReport> report(ReportSelection s) async => WorkReport(
    1,
    '',
    '',
    [
      ReportTotal(
        'job',
        'Studio renovation',
        taskMode ? 'TASK' : 'HOURLY',
        'USD',
        2,
        '14400000',
        '8500',
        taskMode ? '18500' : '34000',
        0,
      ),
    ],
    [
      ReportInterval(
        'session',
        'job',
        'Studio renovation',
        '2026-11-11',
        start,
        start + 14400000,
        '2026-11-11T08:00:00.000Z',
        '2026-11-11T12:00:00.000Z',
        'MANUAL',
      ),
    ],
    const [
      ReportTask(
        'task',
        'job',
        'Fit kitchen units',
        '2026-11-11T12:00:00.000Z',
        'USD',
        '18500',
      ),
    ],
    points,
    const [],
  );
}

void main() {
  testWidgets('empty report location map shows an empty state without crashing', (
    tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          workMapTilesProvider.overrideWithValue((_) => SyntheticTiles()),
        ],
        child: const MaterialApp(
          home: ReportMap(points: [], timezone: 'UTC'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.text('No recorded location samples in this report.'),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  for (final dark in [false, true]) {
    for (final task in [false, true]) {
      testWidgets(
        'render selected ${task ? 'task' : 'hourly'} report ${dark ? 'dark' : 'light'}',
        (tester) async {
          await tester.runAsync(loadMapPreviewFonts);
          tester.view.physicalSize = const Size(420, 920);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final key = GlobalKey();
          await tester.pumpWidget(
            RepaintBoundary(
              key: key,
              child: ProviderScope(
                overrides: [
                  reportApiProvider.overrideWithValue(_VisualReport(task)),
                  timerApiProvider.overrideWithValue(_Timer()),
                ],
                child: MaterialApp(
                  debugShowCheckedModeBanner: false,
                  theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
                  home: const ReportScreen(jobId: 'job'),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          await tester.runAsync(
            () => saveMapPreview(
              key,
              'clockrove-report-${task ? 'task' : 'hourly'}-${dark ? 'dark' : 'light'}',
            ),
          );
          expect(find.text(task ? 'USD 185.00' : 'USD 340.00'), findsWidgets);
          expect(tester.takeException(), isNull);
        },
      );
    }
    testWidgets('render measured report trace ${dark ? 'dark' : 'light'}', (
      tester,
    ) async {
      await tester.runAsync(loadMapPreviewFonts);
      tester.view.physicalSize = const Size(420, 920);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final key = GlobalKey();
      await tester.pumpWidget(
        RepaintBoundary(
          key: key,
          child: ProviderScope(
            overrides: [
              workMapTilesProvider.overrideWithValue((_) => SyntheticTiles()),
            ],
            child: MaterialApp(
              debugShowCheckedModeBanner: false,
              theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
              home: ReportMap(points: _VisualReport.points, timezone: 'UTC'),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.runAsync(
        () => saveMapPreview(
          key,
          'clockrove-report-map-${dark ? 'dark' : 'light'}',
        ),
      );
      expect(tester.takeException(), isNull);
    });
  }
}
