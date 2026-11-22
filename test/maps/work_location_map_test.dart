import 'dart:typed_data';

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/l10n/app_localizations.dart';
import 'package:clockrove/maps/map_provider_settings.dart';
import 'package:clockrove/maps/work_location_map.dart';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/map_preview.dart';

final _tile = TileProvider.transparentImage;

class _ProviderSettingsApi implements MapProviderSettingsApi {
  const _ProviderSettingsApi(this.settings);
  final MapProviderSettings settings;

  @override
  Future<MapProviderSettings> read() async => settings;

  @override
  Future<MapProviderSettings> reset() async => const MapProviderSettings();

  @override
  Future<MapProviderSettings> save(
    MapProviderSettings settings, {
    String? authSecret,
    bool removeCredential = false,
  }) async => settings;

  @override
  Future<Map<String, String>> tileAuthHeaders() async => const {};
}

void main() {
  testWidgets('recorded map uses the selected tile server and attribution', (
    tester,
  ) async {
    const settings = MapProviderSettings(
      provider: 'CUSTOM',
      urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
      attribution: 'Example map data',
      attributionUrl: 'https://example.test/attribution',
      authHeader: 'X-API-Key',
      hasCredential: true,
    );
    MapProviderSettings? usedSettings;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          mapProviderSettingsApiProvider.overrideWithValue(
            const _ProviderSettingsApi(settings),
          ),
          workMapTilesProvider.overrideWithValue((_, value) {
            usedSettings = value;
            return SyntheticTiles();
          }),
        ],
        child: MaterialApp(
          home: WorkLocationMap(
            latitude: 51.5,
            longitude: -.1,
            measuredUtc: DateTime.utc(2026, 11, 11),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final layer = tester.widget<TileLayer>(find.byType(TileLayer).first);
    expect(layer.urlTemplate, settings.urlTemplate);
    expect(find.text(settings.attribution), findsOneWidget);
    expect(usedSettings?.authHeader, settings.authHeader);
    expect(usedSettings?.hasCredential, isTrue);
  });

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
                  mapProviderSettingsApiProvider.overrideWithValue(
                    const TestMapProviderSettingsApi(),
                  ),
                  workMapTilesProvider.overrideWithValue(
                    (_, _) => SyntheticTiles(),
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
          await tester.pumpAndSettle();
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
            find.bySemanticsLabel(AppMessage.recordedLocation.english),
            findsOneWidget,
          );
          final map = tester.widget<FlutterMap>(find.byType(FlutterMap));
          expect(map.mapController!.camera.center.latitude, 51.5);
          expect(map.mapController!.camera.zoom, 15);
          await tester.tap(find.byTooltip(AppMessage.zoomIn.english));
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
            mapProviderSettingsApiProvider.overrideWithValue(
              const TestMapProviderSettingsApi(),
            ),
            workMapTilesProvider.overrideWithValue((_, _) => SyntheticTiles()),
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
      await tester.pumpAndSettle();
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
              tester.getRect(find.byTooltip(AppMessage.zoomOut.english)).bottomRight,
            ),
        isTrue,
      );
      await tester.tap(find.byTooltip(AppMessage.zoomOut.english));
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
            mapProviderSettingsApiProvider.overrideWithValue(
              const TestMapProviderSettingsApi(),
            ),
            workMapTilesProvider.overrideWithValue(
              (_, _) => SyntheticTiles(fail: true),
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
      expect(find.text(AppMessage.mapUnavailable.english), findsOneWidget);
      expect(find.text('Open map'), findsOneWidget);
      expect(find.text('51.500000, -0.100000'), findsOneWidget);
      expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('Timer'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
