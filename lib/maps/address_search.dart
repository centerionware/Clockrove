import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import 'map_provider_settings.dart';

const nominatimSearchEndpoint = 'https://nominatim.openstreetmap.org/search';

class AddressSearchResult {
  const AddressSearchResult({
    required this.displayName,
    required this.latitude,
    required this.longitude,
  });

  final String displayName;
  final double latitude;
  final double longitude;
}

enum AddressSearchFailure { tooShort, rateLimited, unavailable }

class AddressSearchException implements Exception {
  const AddressSearchException(this.failure);

  final AddressSearchFailure failure;

  @override
  String toString() => 'AddressSearchException($failure)';
}

abstract interface class AddressSearchApi {
  /// Searches only when the user explicitly asks; this is not autocomplete.
  Future<List<AddressSearchResult>> search(
    String query, {
    required String languageTag,
  });
}

class NominatimAddressSearchApi implements AddressSearchApi {
  NominatimAddressSearchApi(
    this._client, {
    this.endpoint = nominatimSearchEndpoint,
    this.limit = 6,
    this.minimumInterval = const Duration(seconds: 1),
    this.requestTimeout = const Duration(seconds: 12),
    DateTime Function()? now,
    Future<void> Function(Duration)? wait,
  }) : _now = now ?? DateTime.now,
       _wait = wait ?? Future<void>.delayed;

  final http.Client _client;
  final String endpoint;
  final int limit;
  final Duration minimumInterval;
  final Duration requestTimeout;
  final DateTime Function() _now;
  final Future<void> Function(Duration) _wait;
  final _cache = <String, List<AddressSearchResult>>{};
  DateTime? _lastRequest;

  @override
  Future<List<AddressSearchResult>> search(
    String query, {
    required String languageTag,
  }) async {
    final normalized = query.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (normalized.runes.length < 3) {
      throw const AddressSearchException(AddressSearchFailure.tooShort);
    }
    final cacheKey =
        '${languageTag.toLowerCase()}\u0000${normalized.toLowerCase()}';
    final cached = _cache[cacheKey];
    if (cached != null) return cached;

    // The public Nominatim service allows at most one request per second.
    final last = _lastRequest;
    if (last != null) {
      final earliest = last.add(minimumInterval);
      final now = _now();
      if (now.isBefore(earliest)) {
        await _wait(earliest.difference(now));
      }
    }
    _lastRequest = _now();

    final uri = Uri.parse(endpoint).replace(
      queryParameters: {
        'format': 'jsonv2',
        'q': normalized,
        'limit': '$limit',
        'addressdetails': '0',
      },
    );
    final http.Response response;
    try {
      response = await _client
          .get(
            uri,
            headers: {
              'User-Agent': clockroveMapUserAgent,
              'Accept': 'application/json',
              'Accept-Language': languageTag,
            },
          )
          .timeout(requestTimeout);
    } catch (_) {
      // The request URI includes the typed address; keep it out of errors.
      throw const AddressSearchException(AddressSearchFailure.unavailable);
    }
    if (response.statusCode == 429) {
      throw const AddressSearchException(AddressSearchFailure.rateLimited);
    }
    if (response.statusCode != 200) {
      throw const AddressSearchException(AddressSearchFailure.unavailable);
    }
    final results = _parse(response.bodyBytes);
    if (_cache.length >= 20) _cache.remove(_cache.keys.first);
    _cache[cacheKey] = results;
    return results;
  }

  List<AddressSearchResult> _parse(List<int> body) {
    final Object? decoded;
    try {
      decoded = jsonDecode(utf8.decode(body));
    } catch (_) {
      throw const AddressSearchException(AddressSearchFailure.unavailable);
    }
    if (decoded is! List) {
      throw const AddressSearchException(AddressSearchFailure.unavailable);
    }
    final results = <AddressSearchResult>[];
    for (final item in decoded) {
      if (item is! Map) continue;
      final name = item['display_name'];
      final latitude = double.tryParse('${item['lat']}');
      final longitude = double.tryParse('${item['lon']}');
      if (name is! String ||
          name.trim().isEmpty ||
          latitude == null ||
          longitude == null ||
          !latitude.isFinite ||
          !longitude.isFinite ||
          latitude.abs() > 90 ||
          longitude.abs() > 180) {
        continue;
      }
      results.add(
        AddressSearchResult(
          displayName: name.trim(),
          latitude: latitude,
          longitude: longitude,
        ),
      );
      if (results.length >= limit) break;
    }
    return List.unmodifiable(results);
  }
}

final addressSearchApiProvider = Provider<AddressSearchApi>((ref) {
  final client = http.Client();
  ref.onDispose(client.close);
  return NominatimAddressSearchApi(client);
});
