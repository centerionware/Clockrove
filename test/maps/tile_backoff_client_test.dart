import 'dart:io';

import 'package:clockrove/maps/tile_backoff_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test('429 pauses later requests without retrying and preserves cache headers', () async {
    var now = DateTime.utc(2026, 11, 11);
    var calls = 0;
    final client = TileBackoffClient(MockClient((request) async {
      calls++;
      expect(request.headers['if-none-match'], 'tile-v1');
      expect(request.headers['cache-control'], isNull);
      return http.Response('', calls == 1 ? 429 : 304,
          headers: calls == 1 ? {'retry-after': '120'} : {'etag': 'tile-v1'});
    }), now: () => now, state: TileBackoffState());
    addTearDown(client.close);
    final uri = Uri.parse('https://tiles.example/1/0/0.png');
    expect((await client.get(uri, headers: {'if-none-match': 'tile-v1'})).statusCode, 429);
    await expectLater(client.get(uri), throwsA(isA<http.ClientException>()));
    expect(calls, 1);
    now = now.add(const Duration(seconds: 120));
    expect((await client.get(uri, headers: {'if-none-match': 'tile-v1'})).statusCode, 304);
    expect(calls, 2);
  });

  test('HTTP-date backoff affects only its origin', () async {
    var now = DateTime.utc(2026, 11, 11);
    var calls = 0;
    final client = TileBackoffClient(MockClient((request) async {
      calls++;
      return http.Response('', 503, headers: {
        'retry-after': HttpDate.format(now.add(const Duration(minutes: 3))),
      });
    }), now: () => now, state: TileBackoffState());
    addTearDown(client.close);
    final a = Uri.parse('https://a.example/tile');
    await client.get(a);
    await client.get(Uri.parse('https://b.example/tile'));
    expect(calls, 2);
    now = now.add(const Duration(minutes: 2));
    await expectLater(client.get(a), throwsA(isA<http.ClientException>()));
    now = now.add(const Duration(minutes: 1));
    await client.get(a);
    expect(calls, 3);
  });

  test('closing and reopening a map preserves a shared server pause', () async {
    final state = TileBackoffState();
    final now = DateTime.utc(2026, 11, 11);
    final first = TileBackoffClient(MockClient((_) async => http.Response('', 429,
        headers: {'retry-after': '180'})), now: () => now, state: state);
    final uri = Uri.parse('https://tiles.example/tile');
    await first.get(uri);
    first.close();
    var calls = 0;
    final second = TileBackoffClient(MockClient((_) async {
      calls++;
      return http.Response('', 200);
    }), now: () => now, state: state);
    addTearDown(second.close);
    await expectLater(second.get(uri), throwsA(isA<http.ClientException>()));
    expect(calls, 0);
  });

  test('invalid or past retry dates use a bounded short pause', () async {
    for (final header in ['invalid', '-1', '9999999999999999999999999999999', 'Wed, 11 Nov 2020 00:00:00 GMT']) {
      var now = DateTime.utc(2026, 11, 11);
      var calls = 0;
      final client = TileBackoffClient(MockClient((_) async {
        calls++;
        return http.Response('', 503, headers: {'retry-after': header});
      }), now: () => now, state: TileBackoffState());
      final uri = Uri.parse('https://tiles.example/tile');
      await client.get(uri);
      now = now.add(const Duration(seconds: 29));
      await expectLater(client.get(uri), throwsA(isA<http.ClientException>()));
      expect(calls, 1);
      now = now.add(const Duration(seconds: 1));
      await client.get(uri);
      expect(calls, 2);
      client.close();
    }
  });
}
