import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';

const defaultMapTileUrl = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const defaultMapAttribution = '© OpenStreetMap contributors';
const defaultMapAttributionUrl = 'https://www.openstreetmap.org/copyright';

/// Public runtime tile settings. The secret stays in native protected storage
/// and is fetched separately only when a map needs authenticated tile requests.
class MapProviderSettings {
  const MapProviderSettings({
    this.provider = 'OSM',
    this.urlTemplate = defaultMapTileUrl,
    this.attribution = defaultMapAttribution,
    this.attributionUrl = defaultMapAttributionUrl,
    this.authHeader = 'Authorization',
    this.hasCredential = false,
  });

  final String provider;
  final String urlTemplate;
  final String attribution;
  final String attributionUrl;
  final String authHeader;
  final bool hasCredential;

  bool get usesCustomProvider => provider == 'CUSTOM';

  factory MapProviderSettings.decode(Map<Object?, Object?> value) =>
      MapProviderSettings(
        provider: value['provider'] as String? ?? 'OSM',
        urlTemplate: value['urlTemplate'] as String? ?? defaultMapTileUrl,
        attribution: value['attribution'] as String? ?? defaultMapAttribution,
        attributionUrl:
            value['attributionUrl'] as String? ?? defaultMapAttributionUrl,
        authHeader: value['authHeader'] as String? ?? 'Authorization',
        hasCredential: value['hasCredential'] as bool? ?? false,
      );

  Map<String, Object?> encode({
    String? authSecret,
    bool removeCredential = false,
  }) => {
    'provider': provider,
    'urlTemplate': urlTemplate,
    'attribution': attribution,
    'attributionUrl': attributionUrl,
    'authHeader': authHeader,
    'removeCredential': removeCredential,
    if (authSecret != null && authSecret.isNotEmpty) 'authSecret': authSecret,
  };

  @override
  String toString() =>
      'MapProviderSettings(provider: $provider, '
      'customTileServerConfigured: $usesCustomProvider, '
      'credentialConfigured: $hasCredential)';
}

class MapTileCredential {
  /// Short-lived tile-request data. Diagnostics deliberately omit the secret.
  const MapTileCredential({required this.authHeader, required this.authSecret});

  final String authHeader;
  final String authSecret;

  factory MapTileCredential.decode(Map<Object?, Object?> value) =>
      MapTileCredential(
        authHeader: value['authHeader'] as String? ?? 'Authorization',
        authSecret: value['authSecret'] as String? ?? '',
      );

  Map<String, String> toHeaders() =>
      authSecret.isEmpty ? {} : {authHeader: authSecret};

  @override
  String toString() =>
      'MapTileCredential(credentialConfigured: ${authSecret.isNotEmpty})';
}

const clockroveMapUserAgent =
    'Clockrove/0.1 (io.github.centerionware.clockrove; '
    '+https://github.com/centerionware/Clockrove)';

Map<String, String> mapTileRequestHeaders(MapProviderSettings settings) => {
  'User-Agent': clockroveMapUserAgent,
};

AppMessage? mapProviderSettingsError(
  MapProviderSettings value, {
  String authSecret = '',
}) {
  if (value.provider != 'OSM' && value.provider != 'CUSTOM') {
    return AppMessage.mapUnsupportedProvider;
  }
  if (value.provider == 'OSM') return null;
  if (value.urlTemplate.length > 2048 ||
      !value.urlTemplate.contains('{z}') ||
      !value.urlTemplate.contains('{x}') ||
      !value.urlTemplate.contains('{y}')) {
    return AppMessage.mapMissingTileTokens;
  }
  final replaced = value.urlTemplate
      .replaceAll('{z}', '1')
      .replaceAll('{x}', '1')
      .replaceAll('{y}', '1');
  if (RegExp(r'[{}]').hasMatch(replaced)) {
    return AppMessage.mapUnsupportedTileTokens;
  }
  final uri = Uri.tryParse(replaced);
  if (uri == null ||
      uri.scheme != 'https' ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasFragment) {
    return AppMessage.mapUnsafeTileUrl;
  }
  const sensitiveQueryNames = {
    'key',
    'api_key',
    'apikey',
    'x_api_key',
    'x-api-key',
    'accesskey',
    'access_key',
    'accesstoken',
    'access-token',
    'access_token',
    'client_key',
    'client_secret',
    'clientkey',
    'clientsecret',
    'key_id',
    'keyid',
    'map_key',
    'mapkey',
    'private_key',
    'privatekey',
    'secret_key',
    'secretkey',
    'subscription-key',
    'subscription_key',
    'subscriptionkey',
    'token',
    'auth',
    'authorization',
    'secret',
    'credential',
    'signature',
    'sig',
  };
  if (uri.queryParameters.keys.any(
    (key) => sensitiveQueryNames.contains(key.toLowerCase()),
  )) {
    return AppMessage.mapUrlCredentials;
  }
  if (value.attribution.trim().isEmpty || value.attribution.length > 300) {
    return AppMessage.mapMissingAttribution;
  }
  if (value.attributionUrl.isNotEmpty) {
    final link = Uri.tryParse(value.attributionUrl);
    if (link == null ||
        link.scheme != 'https' ||
        link.host.isEmpty ||
        link.userInfo.isNotEmpty ||
        link.hasFragment ||
        value.attributionUrl.length > 2048) {
      return AppMessage.mapInvalidAttributionLink;
    }
  }
  const reservedHeaders = {
    'user-agent',
    'host',
    'cookie',
    'connection',
    'content-length',
    'accept',
    'referer',
    'origin',
    'proxy-authorization',
  };
  if (value.authHeader.length > 64 ||
      ((value.hasCredential || authSecret.isNotEmpty) &&
          (!RegExp(r'^[A-Za-z0-9-]{1,64}$').hasMatch(value.authHeader) ||
              reservedHeaders.contains(value.authHeader.toLowerCase())))) {
    return AppMessage.mapInvalidAuthHeader;
  }
  if (authSecret.length > 4096 ||
      authSecret.contains('\r') ||
      authSecret.contains('\n')) {
    return AppMessage.mapInvalidAuthValue;
  }
  return null;
}

final class MapProviderInputException extends FormatException {
  MapProviderInputException(this.key) : super(key.english);

  final AppMessage key;
}

abstract interface class MapProviderSettingsApi {
  Future<MapProviderSettings> read();
  Future<MapProviderSettings> save(
    MapProviderSettings settings, {
    String? authSecret,
    bool removeCredential = false,
  });
  Future<MapProviderSettings> reset();
  Future<Map<String, String>> tileAuthHeaders();
}

class MethodChannelMapProviderSettingsApi implements MapProviderSettingsApi {
  const MethodChannelMapProviderSettingsApi();
  static const _channel = MethodChannel('clockrove/map-provider/v1');

  @override
  Future<MapProviderSettings> read() async {
    try {
      final value = await _channel.invokeMapMethod<Object?, Object?>('read');
      if (value == null) return const MapProviderSettings();
      return MapProviderSettings.decode(value);
    } on MissingPluginException {
      // Default OSM maps remain usable on platforms without the Android
      // Keystore adapter. Custom credentials are Android-only for now.
      return const MapProviderSettings();
    } on PlatformException {
      // If Android Keystore data is unavailable or corrupted, preserve map
      // access with the no-key default. The Settings screen can then save or
      // reset a fresh configuration without exposing the platform error.
      return const MapProviderSettings();
    }
  }

  @override
  Future<MapProviderSettings> save(
    MapProviderSettings settings, {
    String? authSecret,
    bool removeCredential = false,
  }) async {
    final error = mapProviderSettingsError(
      settings,
      authSecret: authSecret ?? '',
    );
    if (error != null) throw MapProviderInputException(error);
    final value = await _channel.invokeMapMethod<Object?, Object?>(
      'save',
      settings.encode(
        authSecret: authSecret,
        removeCredential: removeCredential,
      ),
    );
    if (value == null) throw StateError('Map settings could not be saved.');
    return MapProviderSettings.decode(value);
  }

  @override
  Future<MapProviderSettings> reset() async {
    final value = await _channel.invokeMapMethod<Object?, Object?>('reset');
    if (value == null) throw StateError('Map settings could not be reset.');
    return MapProviderSettings.decode(value);
  }

  @override
  Future<Map<String, String>> tileAuthHeaders() async {
    try {
      final value = await _channel.invokeMapMethod<Object?, Object?>(
        'tileCredential',
      );
      if (value == null) return const {};
      return MapTileCredential.decode(value).toHeaders();
    } on MissingPluginException {
      return const {};
    } on PlatformException {
      return const {};
    }
  }
}

final mapProviderSettingsApiProvider = Provider<MapProviderSettingsApi>(
  (ref) => const MethodChannelMapProviderSettingsApi(),
);

final mapProviderSettingsProvider = FutureProvider<MapProviderSettings>(
  (ref) => ref.watch(mapProviderSettingsApiProvider).read(),
);
