import 'package:clockrove/maps/map_provider_settings.dart';
import 'package:clockrove/maps/map_provider_settings_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _SettingsApi implements MapProviderSettingsApi {
  _SettingsApi([this.settings = const MapProviderSettings()]);

  MapProviderSettings settings;
  int saves = 0;
  String? savedCredential;

  @override
  Future<MapProviderSettings> read() async => settings;

  @override
  Future<MapProviderSettings> save(
    MapProviderSettings value, {
    String? authSecret,
    bool removeCredential = false,
  }) async {
    saves++;
    savedCredential = authSecret;
    settings = MapProviderSettings(
      provider: value.provider,
      urlTemplate: value.urlTemplate,
      attribution: value.attribution,
      attributionUrl: value.attributionUrl,
      authHeader: value.authHeader,
      hasCredential:
          value.provider == 'CUSTOM' &&
          !removeCredential &&
          (authSecret?.isNotEmpty == true || value.hasCredential),
    );
    return value;
  }

  @override
  Future<MapProviderSettings> reset() async {
    settings = const MapProviderSettings();
    return settings;
  }

  @override
  Future<Map<String, String>> tileAuthHeaders() async => const {};
}

void main() {
  group('map provider settings validation', () {
    test('defaults to keyless OpenStreetMap', () {
      const settings = MapProviderSettings();

      expect(settings.provider, 'OSM');
      expect(settings.urlTemplate, defaultMapTileUrl);
      expect(settings.hasCredential, isFalse);
      expect(mapProviderSettingsError(settings), isNull);
    });

    test('accepts an attributed HTTPS raster provider', () {
      const settings = MapProviderSettings(
        provider: 'CUSTOM',
        urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
        attribution: 'Example maps',
        attributionUrl: 'https://example.test/terms',
        authHeader: 'X-API-Key',
        hasCredential: true,
      );

      expect(
        mapProviderSettingsError(settings, authSecret: 'private-test-token'),
        isNull,
      );
    });

    test('rejects unsafe URLs, query credentials and invalid headers', () {
      const base = MapProviderSettings(
        provider: 'CUSTOM',
        urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
        attribution: 'Example maps',
      );

      for (final url in [
        'http://tiles.example.test/{z}/{x}/{y}.png',
        'https://user:password@tiles.example.test/{z}/{x}/{y}.png',
        'https://tiles.example.test/{z}/{x}/{y}.png?api_key=private',
        'https://tiles.example.test/{z}/{x}/{y}.png?accessToken=private',
        'https://tiles.example.test/{z}/{x}/{y}.png#fragment',
      ]) {
        expect(
          mapProviderSettingsError(
            MapProviderSettings(
              provider: base.provider,
              urlTemplate: url,
              attribution: base.attribution,
            ),
          ),
          isNotNull,
          reason: url,
        );
      }

      expect(
        mapProviderSettingsError(
          const MapProviderSettings(
            provider: 'CUSTOM',
            urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
            attribution: 'Example maps',
            authHeader: 'Host',
            hasCredential: true,
          ),
        ),
        isNotNull,
      );
    });

    test('credentials stay in the header and out of diagnostics', () {
      const secret = 'Bearer private-test-token';
      const settings = MapProviderSettings(
        provider: 'CUSTOM',
        urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
        attribution: 'Example maps',
        hasCredential: true,
      );
      const credential = MapTileCredential(
        authHeader: 'Authorization',
        authSecret: secret,
      );

      expect(credential.toHeaders()['Authorization'], secret);
      expect(settings.urlTemplate, isNot(contains(secret)));
      expect(settings.toString(), isNot(contains(secret)));
      expect(credential.toString(), isNot(contains(secret)));
      expect(
        mapTileRequestHeaders(settings).containsKey('Authorization'),
        isFalse,
      );
      expect(settings.toString(), contains('credentialConfigured: true'));
    });

    test('unavailable Keystore configuration safely falls back to OSM', () async {
      const channel = MethodChannel('clockrove/map-provider/v1');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            throw PlatformException(code: 'MAP_SETTINGS_UNAVAILABLE');
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding
            .instance
            .defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );

      final result = await const MethodChannelMapProviderSettingsApi().read();

      expect(result.provider, 'OSM');
      expect(result.urlTemplate, defaultMapTileUrl);
      expect(result.hasCredential, isFalse);
    });
  });

  testWidgets('custom provider URL validation prevents saving HTTP tiles', (
    tester,
  ) async {
    final api = _SettingsApi();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [mapProviderSettingsApiProvider.overrideWithValue(api)],
        child: MaterialApp(
          theme: ThemeData(platform: TargetPlatform.android),
          home: const MapProviderSettingsScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('map-provider-choice')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Custom HTTPS tile server').last);
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('map-tile-url')),
      'http://tiles.example.test/{z}/{x}/{y}.png',
    );
    await tester.enterText(
      find.byKey(const ValueKey('map-attribution')),
      'Example maps',
    );
    await tester.ensureVisible(find.text('Save map settings'));
    await tester.tap(find.text('Save map settings'));
    await tester.pumpAndSettle();

    expect(
      find.text('Enter an HTTPS tile URL with {z}, {x}, and {y} placeholders.'),
      findsOneWidget,
    );
    expect(api.saves, 0);
  });

  testWidgets('switching back to OSM removes the custom credential', (
    tester,
  ) async {
    final api = _SettingsApi(
      const MapProviderSettings(
        provider: 'CUSTOM',
        urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
        attribution: 'Example maps',
        hasCredential: true,
      ),
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [mapProviderSettingsApiProvider.overrideWithValue(api)],
        child: MaterialApp(
          theme: ThemeData(platform: TargetPlatform.android),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => Navigator.push<void>(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => const MapProviderSettingsScreen(),
                  ),
                ),
                child: const Text('Configure maps'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Configure maps'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('map-provider-choice')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OpenStreetMap (default)').last);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Save map settings'));
    await tester.tap(find.text('Save map settings'));
    await tester.pumpAndSettle();

    expect(api.saves, 1);
    expect(api.settings.provider, 'OSM');
    expect(api.settings.hasCredential, isFalse);
    expect(api.settings.urlTemplate, defaultMapTileUrl);
  });

  testWidgets('saving public settings keeps the protected credential opaque', (
    tester,
  ) async {
    final api = _SettingsApi(
      const MapProviderSettings(
        provider: 'CUSTOM',
        urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
        attribution: 'Example maps',
        hasCredential: true,
      ),
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [mapProviderSettingsApiProvider.overrideWithValue(api)],
        child: MaterialApp(
          theme: ThemeData(platform: TargetPlatform.android),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => Navigator.push<void>(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => const MapProviderSettingsScreen(),
                  ),
                ),
                child: const Text('Configure maps'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Configure maps'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Save map settings'));
    await tester.tap(find.text('Save map settings'));
    await tester.pumpAndSettle();

    expect(api.saves, 1);
    expect(api.savedCredential, isNull);
    expect(api.settings.hasCredential, isTrue);
  });

  testWidgets('removing a saved credential is an explicit action', (
    tester,
  ) async {
    final api = _SettingsApi(
      const MapProviderSettings(
        provider: 'CUSTOM',
        urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
        attribution: 'Example maps',
        hasCredential: true,
      ),
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [mapProviderSettingsApiProvider.overrideWithValue(api)],
        child: MaterialApp(
          theme: ThemeData(platform: TargetPlatform.android),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => Navigator.push<void>(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => const MapProviderSettingsScreen(),
                  ),
                ),
                child: const Text('Configure maps'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Configure maps'));
    await tester.pumpAndSettle();
    final remove = find.text('Remove saved authentication value');
    await tester.ensureVisible(remove);
    await tester.tap(remove);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Save map settings'));
    await tester.tap(find.text('Save map settings'));
    await tester.pumpAndSettle();

    expect(api.saves, 1);
    expect(api.savedCredential, isNull);
    expect(api.settings.hasCredential, isFalse);
  });
}
