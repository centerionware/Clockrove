import 'dart:convert';

import 'package:clockrove/maps/address_search.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response _results(List<Map<String, Object?>> items) => http.Response(
  jsonEncode(items),
  200,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

http.Response _reverse(Map<String, Object?> item) => http.Response(
  jsonEncode(item),
  200,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

void main() {
  test(
    'sends an explicit keyless Nominatim search with identifying headers',
    () async {
      late http.Request request;
      final api = NominatimAddressSearchApi(
        MockClient((value) async {
          request = value;
          return _results([
            {
              'display_name': '10 Downing Street, London',
              'lat': '51.5034',
              'lon': '-0.1276',
            },
          ]);
        }),
        now: () => DateTime.utc(2026, 11, 26),
        wait: (_) async {},
      );

      final results = await api.search(
        '  10   Downing Street ',
        languageTag: 'de',
      );

      expect(request.url.host, 'nominatim.openstreetmap.org');
      expect(request.url.path, '/search');
      expect(request.url.queryParameters['q'], '10 Downing Street');
      expect(request.url.queryParameters['format'], 'jsonv2');
      expect(request.url.queryParameters['limit'], '6');
      expect(request.url.queryParameters['addressdetails'], '0');
      expect(request.headers['user-agent'], contains('Clockrove'));
      expect(request.headers['accept-language'], 'de');
      expect(results.single.displayName, '10 Downing Street, London');
      expect(results.single.latitude, 51.5034);
      expect(results.single.longitude, -0.1276);
    },
  );

  test('rejects short queries without a network request', () async {
    var calls = 0;
    final api = NominatimAddressSearchApi(
      MockClient((_) async {
        calls++;
        return _results(const []);
      }),
    );

    await expectLater(
      api.search('ab', languageTag: 'en'),
      throwsA(
        isA<AddressSearchException>().having(
          (error) => error.failure,
          'failure',
          AddressSearchFailure.tooShort,
        ),
      ),
    );
    expect(calls, 0);
  });

  test(
    'caches identical searches and waits between different requests',
    () async {
      var now = DateTime.utc(2026, 11, 26);
      final waits = <Duration>[];
      var calls = 0;
      final api = NominatimAddressSearchApi(
        MockClient((_) async {
          calls++;
          return _results([
            {'display_name': 'Example', 'lat': '10', 'lon': '20'},
          ]);
        }),
        now: () => now,
        wait: (duration) async {
          waits.add(duration);
          now = now.add(duration);
        },
      );

      await api.search('Alpha', languageTag: 'en');
      await api.search(' alpha ', languageTag: 'en');
      await api.search('Beta', languageTag: 'en');

      expect(calls, 2);
      expect(waits, [const Duration(seconds: 1)]);
    },
  );

  test('skips malformed results and reports rate limiting', () async {
    final api = NominatimAddressSearchApi(
      MockClient(
        (_) async => _results([
          {'display_name': 7, 'lat': '10', 'lon': '20'},
          {'display_name': 'No coordinates'},
          {'display_name': 'Valid place', 'lat': '10', 'lon': '20'},
        ]),
      ),
      now: () => DateTime.utc(2026, 11, 26),
      wait: (_) async {},
    );

    final results = await api.search('Example', languageTag: 'en');
    expect(results.single.displayName, 'Valid place');

    final limited = NominatimAddressSearchApi(
      MockClient((_) async => http.Response('', 429)),
      now: () => DateTime.utc(2026, 11, 26),
      wait: (_) async {},
    );
    await expectLater(
      limited.search('Another place', languageTag: 'en'),
      throwsA(
        isA<AddressSearchException>().having(
          (error) => error.failure,
          'failure',
          AddressSearchFailure.rateLimited,
        ),
      ),
    );
  });

  test('reverse lookup returns a house-level address for a property', () async {
    late http.Request request;
    final api = NominatimPropertyAddressLookup(
      MockClient((value) async {
        request = value;
        return _reverse({
          'display_name': '123 Main Street, Fort Collins, Colorado, 80521',
          'address': {
            'house_number': '123',
            'road': 'Main Street',
            'city': 'Fort Collins',
            'state': 'Colorado',
            'postcode': '80521',
          },
        });
      }),
      now: () => DateTime.utc(2026, 11, 28),
      wait: (_) async {},
    );

    final address = await api.lookup(
      40.59142,
      -105.100065,
      languageTag: 'en-US',
    );

    expect(request.url.path, '/reverse');
    expect(request.url.queryParameters['lat'], '40.591420');
    expect(request.url.queryParameters['lon'], '-105.100065');
    expect(request.url.queryParameters['zoom'], '18');
    expect(request.url.queryParameters['addressdetails'], '1');
    expect(request.headers['user-agent'], contains('Clockrove'));
    expect(request.headers['accept-language'], 'en-US');
    expect(address, '123 Main Street, Fort Collins, Colorado, 80521');
  });

  test(
    'reverse lookup refuses a street or locality without a house number',
    () async {
      final api = NominatimPropertyAddressLookup(
        MockClient(
          (_) async => _reverse({
            'display_name': 'Fort Collins, Colorado',
            'address': {
              'road': 'Main Street',
              'city': 'Fort Collins',
              'state': 'Colorado',
            },
          }),
        ),
        now: () => DateTime.utc(2026, 11, 28),
        wait: (_) async {},
      );

      expect(
        await api.lookup(40.59142, -105.100065, languageTag: 'en'),
        isNull,
      );
    },
  );

  test(
    'reverse lookup rejects invalid coordinates without a request',
    () async {
      var calls = 0;
      final api = NominatimPropertyAddressLookup(
        MockClient((_) async {
          calls++;
          return _results(const []);
        }),
      );

      await expectLater(
        api.lookup(double.nan, 0, languageTag: 'en'),
        throwsArgumentError,
      );
      expect(calls, 0);
    },
  );

  test(
    'reports an unavailable service for server or payload failures',
    () async {
      for (final response in [
        http.Response('', 500),
        http.Response('not json', 200),
      ]) {
        final api = NominatimAddressSearchApi(
          MockClient((_) async => response),
          now: () => DateTime.utc(2026, 11, 26),
          wait: (_) async {},
        );
        await expectLater(
          api.search('Example place', languageTag: 'en'),
          throwsA(
            isA<AddressSearchException>().having(
              (error) => error.failure,
              'failure',
              AddressSearchFailure.unavailable,
            ),
          ),
        );
      }
    },
  );
}
