import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import 'map_provider_settings.dart';

const nominatimSearchEndpoint = 'https://nominatim.openstreetmap.org/search';
const nominatimReverseEndpoint = 'https://nominatim.openstreetmap.org/reverse';

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

abstract interface class PropertyAddressLookupApi {
  /// Returns a house-level address, or null when the coordinates only resolve
  /// to a street or locality. Callers must not present a street as a property.
  Future<String?> lookup(
    double latitude,
    double longitude, {
    required String languageTag,
  });
}

class NominatimPropertyAddressLookup implements PropertyAddressLookupApi {
  NominatimPropertyAddressLookup(
    this._client, {
    this.endpoint = nominatimReverseEndpoint,
    this.minimumInterval = const Duration(seconds: 1),
    this.requestTimeout = const Duration(seconds: 12),
    DateTime Function()? now,
    Future<void> Function(Duration)? wait,
  }) : _now = now ?? DateTime.now,
       _wait = wait ?? Future<void>.delayed;

  final http.Client _client;
  final String endpoint;
  final Duration minimumInterval;
  final Duration requestTimeout;
  final DateTime Function() _now;
  final Future<void> Function(Duration) _wait;
  final _cache = <String, String?>{};
  DateTime? _lastRequest;

  @override
  Future<String?> lookup(
    double latitude,
    double longitude, {
    required String languageTag,
  }) async {
    if (!latitude.isFinite ||
        !longitude.isFinite ||
        latitude.abs() > 90 ||
        longitude.abs() > 180) {
      throw ArgumentError('Invalid coordinates');
    }
    final cacheKey =
        '${languageTag.toLowerCase()}\u0000${latitude.toStringAsFixed(6)},'
        '${longitude.toStringAsFixed(6)}';
    if (_cache.containsKey(cacheKey)) return _cache[cacheKey];

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
        'lat': latitude.toStringAsFixed(6),
        'lon': longitude.toStringAsFixed(6),
        'zoom': '18',
        'addressdetails': '1',
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
      throw const AddressSearchException(AddressSearchFailure.unavailable);
    }
    if (response.statusCode == 429) {
      throw const AddressSearchException(AddressSearchFailure.rateLimited);
    }
    if (response.statusCode != 200) {
      throw const AddressSearchException(AddressSearchFailure.unavailable);
    }
    final result = _parse(response.bodyBytes);
    if (_cache.length >= 20) _cache.remove(_cache.keys.first);
    _cache[cacheKey] = result;
    return result;
  }

  String? _parse(List<int> body) {
    final Object? decoded;
    try {
      decoded = jsonDecode(utf8.decode(body));
    } catch (_) {
      throw const AddressSearchException(AddressSearchFailure.unavailable);
    }
    if (decoded is! Map || decoded['address'] is! Map) return null;
    final address = decoded['address'] as Map;
    String text(Object? value) => value is String ? value.trim() : '';
    final houseNumber = text(address['house_number']);
    final road = text(address['road']);
    if (houseNumber.isEmpty || road.isEmpty) return null;
    final locality = [
      address['city'],
      address['town'],
      address['village'],
      address['municipality'],
      address['hamlet'],
    ].map(text).firstWhere((value) => value.isNotEmpty, orElse: () => '');
    final region = [
      address['state'],
      address['province'],
      address['region'],
    ].map(text).firstWhere((value) => value.isNotEmpty, orElse: () => '');
    final postcode = text(address['postcode']);
    if (locality.isEmpty && postcode.isEmpty) return null;
    return [
      '$houseNumber $road',
      if (locality.isNotEmpty) locality,
      if (region.isNotEmpty) region,
      if (postcode.isNotEmpty) postcode,
    ].join(', ');
  }
}

final addressSearchApiProvider = Provider<AddressSearchApi>((ref) {
  final client = http.Client();
  ref.onDispose(client.close);
  return NominatimAddressSearchApi(client);
});

final propertyAddressLookupApiProvider = Provider<PropertyAddressLookupApi>((
  ref,
) {
  final client = http.Client();
  ref.onDispose(client.close);
  return NominatimPropertyAddressLookup(client);
});
