import 'dart:async';
import 'dart:typed_data';

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/automation/automation_api.dart';
import 'package:clockrove/automation/automation_screen.dart';
import 'package:clockrove/automation/site_map_picker.dart';
import 'package:clockrove/maps/work_location_map.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/automation_fixture.dart';
import 'support/map_preview.dart';

class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
}

Future<void> _show(
  WidgetTester tester,
  FixtureAutomationApi api, {
  bool failTiles = false,
  bool dark = false,
  Widget? home,
  GlobalKey? boundary,
}) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundary,
      child: ProviderScope(
        overrides: [
          automationApiProvider.overrideWithValue(api),
          timerApiProvider.overrideWithValue(_Timer()),
          workMapTilesProvider.overrideWithValue(
            (_) => SyntheticTiles(fail: failTiles),
          ),
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

Future<void> _newSite(WidgetTester tester) async {
  await _reveal(tester, 'Add work site');
  await tester.tap(find.text('Add work site'));
  await tester.pumpAndSettle();
  await tester.enterText(
    find.byType(TextFormField).at(0),
    'Synthetic selected place',
  );
}

Future<void> _current(WidgetTester tester) async {
  await _reveal(tester, 'Use current location');
  await tester.tap(find.text('Use current location'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Continue'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'a single explained fix fills a draft and saves only after explicit confirmation',
    (tester) async {
      final api = FixtureAutomationApi();
      await _show(tester, api);
      await _newSite(tester);
      await _reveal(tester, 'Use current location');
      await tester.tap(find.text('Use current location'));
      await tester.pumpAndSettle();
      expect(find.textContaining('not saved as work history'), findsOneWidget);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
      expect(api.positionRequests, 0);
      await _current(tester);
      expect(api.positionRequests, 1);
      expect(api.preciseRequests, 0);
      expect(api.attempts, isEmpty);
      expect(api.sites, isEmpty);
      expect(find.textContaining('25 m accuracy'), findsOneWidget);
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(api.sites.single.latitude, 51.5);
      expect(api.sites.single.longitude, -0.1);
      expect(api.attempts, hasLength(1));
    },
  );
  testWidgets(
    'current-location button requests precise permission only when it is missing',
    (tester) async {
      final api = FixtureAutomationApi()..precise = false;
      api.positionResult = () async {
        if (!api.precise) {
          throw PlatformException(code: 'PRECISE_REQUIRED');
        }
        return SitePosition(10, 20, 25, DateTime.utc(2026, 11, 11, 12));
      };
      await _show(tester, api);
      await _newSite(tester);
      await _current(tester);
      expect(api.preciseRequests, 1);
      expect(api.backgroundRequests, 0);
      expect(api.positionRequests, 2);
      expect(api.sites, isEmpty);
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(api.sites.single.latitude, 10);
      expect(api.sites.single.longitude, 20);
    },
  );
  testWidgets(
    'unavailable current fix preserves manual coordinate entry and save',
    (tester) async {
      final api = FixtureAutomationApi()
        ..positionResult = () async =>
            throw PlatformException(code: 'POSITION_UNAVAILABLE');
      await _show(tester, api);
      await _newSite(tester);
      await _current(tester);
      expect(
        find.textContaining('No fresh location is available'),
        findsOneWidget,
      );
      await tester.enterText(find.byType(TextFormField).at(1), '10');
      await tester.enterText(find.byType(TextFormField).at(2), '20');
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(api.sites.single.latitude, 10);
      expect(api.sites.single.longitude, 20);
    },
  );
  testWidgets(
    'closing the editor cancels a pending fix and ignores its late result',
    (tester) async {
      final pending = Completer<SitePosition>();
      final api = FixtureAutomationApi()..positionResult = () => pending.future;
      await _show(tester, api);
      await _newSite(tester);
      await _reveal(tester, 'Use current location');
      await tester.tap(find.text('Use current location'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Continue'));
      await tester.pump();
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(api.positionCancellations, 1);
      pending.complete(SitePosition(10, 20, 25, DateTime.utc(2026)));
      await tester.pumpAndSettle();
      expect(api.attempts, isEmpty);
      expect(api.sites, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'map tap returns a centre without saving until the site form is confirmed',
    (tester) async {
      final api = FixtureAutomationApi();
      await _show(tester, api);
      await _newSite(tester);
      await tester.enterText(find.byType(TextFormField).at(1), '51.5');
      await tester.enterText(find.byType(TextFormField).at(2), '-0.1');
      await _reveal(tester, 'Choose on map');
      await tester.tap(find.text('Choose on map'));
      await tester.pumpAndSettle();
      expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
      expect(find.byTooltip('Zoom in'), findsOneWidget);
      expect(find.byTooltip('Zoom out'), findsOneWidget);
      await tester.tapAt(
        tester.getCenter(find.byType(FlutterMap)) + const Offset(40, 30),
      );
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Use this centre'));
      await tester.pumpAndSettle();
      expect(api.attempts, isEmpty);
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(api.sites.single.latitude, lessThan(51.5));
      expect(api.sites.single.longitude, greaterThan(-0.1));
    },
  );
  testWidgets(
    'an unset site map requests an explained fresh fix and opens at that location',
    (tester) async {
      final api = FixtureAutomationApi();
      await _show(tester, api);
      await _newSite(tester);
      await _reveal(tester, 'Choose on map');
      await tester.tap(find.text('Choose on map'));
      await tester.pumpAndSettle();
      expect(find.text('Use your current location?'), findsOneWidget);
      expect(api.positionRequests, 0);
      await tester.tap(find.text('Continue'));
      await tester.pumpAndSettle();
      expect(api.positionRequests, 1);
      final map = tester.widget<FlutterMap>(find.byType(FlutterMap));
      expect(map.mapController!.camera.center.latitude, 51.5);
      expect(map.mapController!.camera.center.longitude, -0.1);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'map failure keeps attribution and permits returning to coordinate setup',
    (tester) async {
      final api = FixtureAutomationApi();
      await _show(tester, api, failTiles: true);
      await _newSite(tester);
      await _reveal(tester, 'Choose on map');
      await tester.tap(find.text('Choose on map'));
      await tester.pumpAndSettle();
      await tester.runAsync(
        () => precacheImage(
          MemoryImage(Uint8List(0)),
          tester.element(find.byType(FlutterMap)),
          onError: (_, _) {},
        ),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('Map tiles are unavailable'), findsOneWidget);
      expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Use this centre'),
            )
            .onPressed,
        isNull,
      );
      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).at(1), '10');
      await tester.enterText(find.byType(TextFormField).at(2), '20');
      await _reveal(tester, 'Save site');
      await tester.tap(find.text('Save site'));
      await tester.pumpAndSettle();
      expect(api.sites, hasLength(1));
    },
  );
  testWidgets(
    'polar coordinates are preserved through an unsupported map view',
    (tester) async {
      await _show(
        tester,
        FixtureAutomationApi(),
        home: const SiteMapPicker(latitude: 89, longitude: 20, radius: 150),
      );
      expect(
        find.textContaining('cannot display polar locations'),
        findsOneWidget,
      );
      expect(find.byType(MarkerLayer), findsNothing);
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Use this centre'),
            )
            .onPressed,
        isNull,
      );
      expect(tester.takeException(), isNull);
    },
  );
  for (final dark in [false, true]) {
    testWidgets('render accessible site map ${dark ? 'dark' : 'light'}', (
      tester,
    ) async {
      final semantics = tester.ensureSemantics();
      tester.view.physicalSize = const Size(420, 920);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      try {
        await tester.runAsync(loadMapPreviewFonts);
        final key = GlobalKey();
        await _show(
          tester,
          FixtureAutomationApi(),
          dark: dark,
          boundary: key,
          home: const SiteMapPicker(
            latitude: 51.5,
            longitude: -0.1,
            radius: 150,
          ),
        );
        await tester.runAsync(
          () => precacheImage(
            MemoryImage(TileProvider.transparentImage),
            tester.element(find.byType(FlutterMap)),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.bySemanticsLabel('Selected site centre'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.runAsync(
          () => saveMapPreview(
            key,
            'clockrove-site-map-${dark ? 'dark' : 'light'}',
          ),
        );
        await tester.pumpWidget(const SizedBox.shrink());
      } finally {
        semantics.dispose();
      }
    });
  }
}
