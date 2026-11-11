import 'dart:typed_data';

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/l10n/app_strings.dart';
import 'package:clockrove/maps/work_location_map.dart';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/map_preview.dart';

final _tile = TileProvider.transparentImage;

void main() {
  for (final dark in [false, true]) {
    testWidgets(
      'recorded point, accuracy, attribution and accessible zoom ${dark ? "dark" : "light"}',
      (tester) async {
        final semantics = tester.ensureSemantics();
        try {
          tester.view.physicalSize = const Size(420, 920);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          await tester.runAsync(loadMapPreviewFonts);
          final boundary = GlobalKey();
          await tester.pumpWidget(
            RepaintBoundary(
              key: boundary,
              child: ProviderScope(
                overrides: [
                  workMapTilesProvider.overrideWithValue(
                    (_) => SyntheticTiles(),
                  ),
                ],
                child: MaterialApp(
                  debugShowCheckedModeBanner: false,
                  theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
                  home: WorkLocationMap(
                    latitude: 51.5,
                    longitude: -0.1,
                    measuredUtc: DateTime.utc(2026, 11, 11, 10),
                    accuracyMeters: 80,
                  ),
                ),
              ),
            ),
          );
          await tester.runAsync(
            () => precacheImage(
              MemoryImage(_tile),
              tester.element(find.byType(FlutterMap)),
            ),
          );
          await tester.pumpAndSettle();
          expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
          expect(find.text('80 m accuracy'), findsOneWidget);
          expect(
            find.bySemanticsLabel(AppStrings.recordedLocation),
            findsOneWidget,
          );
          final map = tester.widget<FlutterMap>(find.byType(FlutterMap));
          expect(map.mapController!.camera.center.latitude, 51.5);
          expect(map.mapController!.camera.zoom, 15);
          await tester.tap(find.byTooltip(AppStrings.zoomIn));
          await tester.pumpAndSettle();
          expect(map.mapController!.camera.zoom, 16);
          expect(tester.takeException(), isNull);
          await tester.runAsync(
            () => saveMapPreview(
              boundary,
              'clockrove-map-${dark ? "dark" : "light"}',
            ),
          );
          await tester.pumpWidget(const SizedBox.shrink());
        } finally {
          semantics.dispose();
        }
      },
    );
  }
  testWidgets(
    'large landscape text keeps attribution and map controls usable',
    (tester) async {
      tester.view.physicalSize = const Size(640, 320);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            workMapTilesProvider.overrideWithValue((_) => SyntheticTiles()),
          ],
          child: MaterialApp(
            theme: ClockroveTheme.light(),
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: const TextScaler.linear(1.8)),
              child: child!,
            ),
            home: WorkLocationMap(
              latitude: 51.5,
              longitude: -0.1,
              measuredUtc: DateTime.utc(2026, 11, 11),
              accuracyMeters: 80,
            ),
          ),
        ),
      );
      await tester.runAsync(
        () => precacheImage(
          MemoryImage(_tile),
          tester.element(find.byType(FlutterMap)),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
      expect(
        tester
            .getRect(find.byType(FlutterMap))
            .contains(
              tester.getRect(find.byTooltip(AppStrings.zoomOut)).bottomRight,
            ),
        isTrue,
      );
      await tester.tap(find.byTooltip(AppStrings.zoomOut));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
  testWidgets(
    'tile failures preserve attribution and navigation back to the timer',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            workMapTilesProvider.overrideWithValue(
              (_) => SyntheticTiles(fail: true),
            ),
          ],
          child: MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: TextButton(
                  onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) => WorkLocationMap(
                        latitude: 51.5,
                        longitude: -0.1,
                        measuredUtc: DateTime.utc(2026, 11, 11),
                      ),
                    ),
                  ),
                  child: const Text('Timer'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Timer'));
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        // Wait for the actual image decoder, which rejects the synthetic corrupt bytes.
        await precacheImage(
          MemoryImage(Uint8List(0)),
          tester.element(find.byType(FlutterMap)),
          onError: (_, _) {},
        );
      });
      await tester.pumpAndSettle();
      expect(find.text(AppStrings.mapUnavailable), findsOneWidget);
      expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('Timer'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
