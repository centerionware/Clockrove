import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/automation/automation_api.dart';
import 'package:clockrove/automation/automation_screen.dart';
import 'package:clockrove/automation/site_editor.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/automation_fixture.dart';
import 'support/localized_copy.dart';
import 'support/map_preview.dart';

class _Timer extends TimerApi {
  final calls = <TimerIntent>[];
  int snapshotCalls = 0;
  TimerSnapshot value = const TimerSnapshot(1, [
    JobSummary('job', 'Studio renovation'),
  ], null);
  @override
  Stream<int> get committedVersions => const Stream.empty();

  @override
  Future<TimerSnapshot> snapshot() async {
    snapshotCalls++;
    return value;
  }

  @override
  Future<TimerOutcome> execute({
    required String id,
    required int version,
    required TimerAction action,
    String? jobId,
  }) async {
    calls.add(TimerIntent(id, version, action, jobId));
    return TimerOutcome(version + 1, null);
  }
}

Future<void> _show(
  WidgetTester tester,
  FixtureAutomationApi api, {
  bool dark = false,
  Widget? home,
  Key? boundary,
  TimerApi? timerApi,
}) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundary,
      child: ProviderScope(
        overrides: [
          automationApiProvider.overrideWithValue(api),
          timerApiProvider.overrideWithValue(timerApi ?? _Timer()),
        ],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
          home:
              home ??
              const AutomationScreen(
                jobId: 'job',
                jobName: 'Studio renovation',
              ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _reveal(WidgetTester tester, String label) async {
  final scrollable = find.byType(Scrollable).first;
  tester.state<ScrollableState>(scrollable).position.jumpTo(0);
  await tester.pump();
  await tester.scrollUntilVisible(
    find.text(label),
    200,
    scrollable: scrollable,
  );
  await tester.pumpAndSettle();
}

FixtureAutomationApi _configured() => FixtureAutomationApi()
  ..sites.add(const WorkSite('site', 'Riverside studio', 51.5, -0.1, 150, true))
  ..settings = const SiteSettings(enabled: true, autoStart: true);

Future<void> _saveSiteFromCurrentLocation(WidgetTester tester) async {
  await _reveal(tester, 'Riverside studio');
  await tester.tap(find.text('Riverside studio'));
  await tester.pumpAndSettle();
  await _reveal(tester, 'Use current location');
  await tester.tap(find.text('Use current location'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Continue'));
  await tester.pumpAndSettle();
  await _reveal(tester, 'Save site');
  await tester.tap(find.text('Save site'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'site editor validates finite coordinates and radius before returning',
    (tester) async {
      await _show(tester, FixtureAutomationApi(), home: const SiteEditor());
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(find.text('Enter a site name.'), findsOneWidget);
      await tester.enterText(
        find.byType(TextFormField).at(0),
        'Synthetic place',
      );
      await tester.enterText(find.byType(TextFormField).at(1), 'NaN');
      await tester.enterText(find.byType(TextFormField).at(2), '181');
      await tester.enterText(find.byType(TextFormField).at(3), '1.79');
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(find.text('Enter a number from -90 to 90.'), findsOneWidget);
      expect(find.text('Enter a number from -180 to 180.'), findsOneWidget);
      expect(find.text('Enter a number from 1.8 to 10000.'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'add and remove a site persist through the typed command adapter',
    (tester) async {
      final api = FixtureAutomationApi();
      await _show(tester, api);
      await _reveal(tester, 'Add work site');
      await tester.tap(find.text('Add work site'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byType(TextFormField).at(0),
        'Riverside studio',
      );
      await tester.enterText(find.byType(TextFormField).at(1), '51.5');
      await tester.enterText(find.byType(TextFormField).at(2), '-0.1');
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(api.sites.single.radius, 150);
      expect(api.settings.enabled, isFalse);
      await _reveal(tester, 'Riverside studio');
      await tester.tap(find.text('Riverside studio'));
      await tester.pumpAndSettle();
      await _reveal(tester, 'Remove site');
      await tester.tap(find.text('Remove site'));
      await tester.pumpAndSettle();
      expect(api.sites, hasLength(1));
      await tester.tap(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.widgetWithText(TextButton, 'Remove site'),
        ),
      );
      await tester.pumpAndSettle();
      expect(api.sites, isEmpty);
    },
  );
  testWidgets('saving a current location offers an explicit clock-in command', (
    tester,
  ) async {
    final api = _configured();
    final timer = _Timer();
    await _show(tester, api, timerApi: timer);
    // Open and save after taking the one-shot setup fix.
    await _saveSiteFromCurrentLocation(tester);
    expect(api.positionRequests, 1);
    expect(find.text('Clock in at this site now?'), findsOneWidget);
    expect(timer.calls, isEmpty);
    expect(
      find.textContaining(
        'Changing a site while you are here resets arrival detection',
      ),
      findsOneWidget,
    );
    await tester.tap(find.text(englishCopy(AppMessage.clockIn)));
    await tester.pumpAndSettle();
    expect(timer.calls, hasLength(1));
    expect(timer.calls.single.action, TimerAction.start);
    expect(timer.calls.single.jobId, 'job');
    expect(find.text('Clocked in to Studio renovation.'), findsOneWidget);
  });
  testWidgets('declining the in-place clock-in offer leaves work stopped', (
    tester,
  ) async {
    final timer = _Timer();
    await _show(tester, _configured(), timerApi: timer);
    await _saveSiteFromCurrentLocation(tester);
    await tester.tap(find.text('Not now'));
    await tester.pumpAndSettle();
    expect(timer.calls, isEmpty);
  });
  testWidgets('in-place offer never switches another running job', (
    tester,
  ) async {
    final timer = _Timer()
      ..value = TimerSnapshot(
        1,
        const [JobSummary('job', 'Studio renovation')],
        RunningSession(
          'other-session',
          'other-job',
          'Other job',
          DateTime.utc(2026, 11, 22),
        ),
      );
    await _show(tester, _configured(), timerApi: timer);
    await _saveSiteFromCurrentLocation(tester);
    expect(timer.snapshotCalls, 1);
    expect(timer.value.running!.jobId, 'other-job');
    expect(
      find.text(
        'Another job is running. Clockrove will not switch jobs automatically.',
      ),
      findsOneWidget,
    );
    expect(find.text('Clock in at this site now?'), findsNothing);
    expect(timer.calls, isEmpty);
  });
  testWidgets(
    'saving a current fix while this job runs keeps it without a second clock-in',
    (tester) async {
      final api = _configured();
      final timer = _Timer()
        ..value = TimerSnapshot(
          1,
          const [JobSummary('job', 'Studio renovation')],
          RunningSession(
            'session',
            'job',
            'Studio renovation',
            DateTime.utc(2026, 11, 22),
          ),
        );
      await _show(tester, api, timerApi: timer);
      await _saveSiteFromCurrentLocation(tester);
      expect(api.savedCurrentFix, isNotNull);
      expect(api.savedCurrentFix!.accuracy, 25);
      expect(timer.snapshotCalls, 1);
      expect(timer.calls, isEmpty);
      expect(find.text('This job is already running.'), findsOneWidget);
      expect(find.text('Clock in at this site now?'), findsNothing);
    },
  );
  testWidgets('small radius warns that Android may miss arrivals', (
    tester,
  ) async {
    final api = FixtureAutomationApi()
      ..sites.add(const WorkSite('site', 'Tight site', 51.5, -0.1, 15, true))
      ..settings = const SiteSettings(enabled: true, autoStart: true);
    await _show(tester, api);
    await _reveal(tester, 'Tight site');
    expect(
      find.textContaining('Android may miss arrivals at this radius'),
      findsOneWidget,
    );
  });
  testWidgets(
    'permission explanation can be declined without enabling automation',
    (tester) async {
      final api = FixtureAutomationApi()
        ..precise = false
        ..background = false;
      await _show(tester, api);
      await _reveal(tester, 'Grant precise location');
      await tester.tap(find.text('Grant precise location'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('without keeping a travel history'),
        findsOneWidget,
      );
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
      expect(api.preciseRequests, 0);
      expect(api.settings.enabled, isFalse);
      await _reveal(tester, 'Grant precise location');
      await tester.tap(find.text('Grant precise location'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Continue'));
      await tester.pumpAndSettle();
      expect(api.preciseRequests, 1);
      expect(api.backgroundRequests, 0);
      expect(api.settings.enabled, isFalse);
      expect(find.text('Allow background location'), findsOneWidget);
    },
  );
  testWidgets(
    'lost command response retries the original id and displayed version',
    (tester) async {
      final api = _configured()..loseNextResponse = true;
      await _show(tester, api);
      await _reveal(tester, 'Use work site automation');
      await tester.tap(find.text('Use work site automation'));
      await tester.pumpAndSettle();
      expect(api.settings.enabled, isFalse);
      expect(api.attempts, hasLength(1));
      await _reveal(tester, 'Retry change');
      await tester.tap(find.text('Retry change'));
      await tester.pumpAndSettle();
      expect(api.attempts, hasLength(2));
      expect(api.attempts[0], api.attempts[1]);
      expect(api.version, 2);
      expect(find.text('Retry change'), findsNothing);
    },
  );
  testWidgets('grace accepts only whole minutes through one day', (
    tester,
  ) async {
    final api = _configured();
    await _show(tester, api);
    await _reveal(tester, 'Departure grace');
    await tester.tap(find.text('Departure grace'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), '1441');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(
      find.text(englishCopy(AppMessage.automationInvalidGrace)),
      findsOneWidget,
    );
    expect(api.attempts, isEmpty);
    await tester.enterText(find.byType(TextFormField), '0');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(api.settings.graceMinutes, 0);
  });
  testWidgets('review shows recorded boundaries before explicit acceptance', (
    tester,
  ) async {
    final api = _configured()
      ..departed = DateTime.utc(2026, 11, 11, 11)
      ..deadline = DateTime.utc(2026, 11, 11, 11, 30)
      ..reviews = [
        WorkReview('session', DateTime.utc(2026, 11, 11, 9), null, false),
      ];
    await _show(tester, api);
    await _reveal(tester, 'Departure pending');
    expect(
      find.textContaining(
        'Automatic departure is paused while this work needs review',
      ),
      findsOneWidget,
    );
    await _reveal(tester, 'Keep recorded times');
    expect(find.textContaining('Still running'), findsOneWidget);
    final record = tester.widget<Text>(find.textContaining('Start:'));
    expect(record.data, contains('2026'));
    await tester.tap(find.text('Keep recorded times'));
    await tester.pumpAndSettle();
    expect(api.attempts, isEmpty);
    expect(find.textContaining('ongoing timer keeps running'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Keep recorded times'));
    await tester.pumpAndSettle();
    expect(api.acceptedReviews.single.end, isNull);
    expect(api.reviews, isEmpty);
    expect(api.departed, isNull);
  });
  testWidgets('large text and long names remain scrollable without overflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = _configured();
    await _show(
      tester,
      api,
      home: MediaQuery(
        data: const MediaQueryData(textScaler: TextScaler.linear(2)),
        child: const AutomationScreen(
          jobId: 'job',
          jobName: 'A very long worksite name with enough words to wrap over several lines',
        ),
      ),
    );
    await _reveal(tester, 'Departure grace');
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'provider retry reports actual health without pretending registration succeeded',
    (tester) async {
      final api = _configured()..monitoringHealth = 'UNAVAILABLE';
      await _show(tester, api);
      await tester.tap(find.text('Retry site monitoring'));
      await tester.pumpAndSettle();
      expect(api.monitoringRetries, 1);
      expect(find.text('Site monitoring needs attention'), findsOneWidget);
      expect(api.settingsVisits, 0);
      expect(api.attempts, isEmpty);
      api.retryUnavailable = true;
      await tester.tap(find.text('Retry site monitoring'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('Site monitoring could not be retried'),
        findsOneWidget,
      );
    },
  );
  for (final dark in [false, true]) {
    testWidgets(
      'render work sites, editor and provisional review ${dark ? 'dark' : 'light'}',
      (tester) async {
        await tester.runAsync(loadMapPreviewFonts);
        tester.view.physicalSize = const Size(420, 920);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final api = _configured();
        final boundary = GlobalKey();
        Future<void> capture(String name) async {
          await tester.runAsync(() async {
            final render =
                boundary.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary;
            final image = await render.toImage(pixelRatio: 2);
            final bytes = await image.toByteData(
              format: ui.ImageByteFormat.png,
            );
            Directory('build/ui-preview').createSync(recursive: true);
            await File(
              'build/ui-preview/clockrove-$name-${dark ? 'dark' : 'light'}.png',
            ).writeAsBytes(bytes!.buffer.asUint8List());
            image.dispose();
          });
        }

        await _show(tester, api, dark: dark, boundary: boundary);
        await capture('sites');
        await _show(
          tester,
          api,
          dark: dark,
          boundary: boundary,
          home: const SiteEditor(
            site: WorkSite('site', 'Riverside studio', 51.5, -0.1, 150, true),
          ),
        );
        await capture('site-editor');
        api.departed = DateTime.utc(2026, 11, 11, 11);
        api.deadline = DateTime.utc(2026, 11, 11, 11, 30);
        api.reviews = [
          WorkReview('session', DateTime.utc(2026, 11, 11, 9), null, false),
        ];
        await _show(tester, api, dark: dark, boundary: boundary);
        await capture('departure');
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }
}
