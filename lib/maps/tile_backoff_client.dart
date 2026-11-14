import 'dart:async';
import 'dart:io';

import 'package:http/http.dart' as http;

/// Shared across map screens for the duration of the app process.
class TileBackoffState {
  final _retryAfter = <String, DateTime>{};
}

/// No automatic retries: later visible-tile requests respect the server's pause.
class TileBackoffClient extends http.BaseClient {
  TileBackoffClient(
    this._inner, {
    DateTime Function()? now,
    TileBackoffState? state,
    this.requestTimeout = const Duration(seconds: 12),
    this._protectedHeaders,
  }) : _now = now ?? DateTime.now,
       _state = state ?? _sharedState;

  static final _sharedState = TileBackoffState();
  final TileBackoffState _state;
  final Duration requestTimeout;
  final Future<Map<String, String>> Function()? _protectedHeaders;

  final http.Client _inner;
  final DateTime Function() _now;
  Future<Map<String, String>>? _loadedProtectedHeaders;

  Future<Map<String, String>> _loadProtectedHeaders() async {
    final loader = _protectedHeaders;
    if (loader == null) return const {};
    return _loadedProtectedHeaders ??= loader();
  }

  void resetProtectedHeaders() => _loadedProtectedHeaders = null;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    // Provider credentials are carried only in headers. Do not forward them
    // across an HTTP redirect, including one to another host.
    request.followRedirects = false;
    try {
      request.headers.addAll(await _loadProtectedHeaders());
    } catch (_) {
      throw http.ClientException('Map tile credentials are unavailable');
    }
    final origin = request.url.origin;
    final now = _now().toUtc();
    final until = _state._retryAfter[origin];
    if (until != null && now.isBefore(until)) {
      throw http.ClientException('Map server is temporarily unavailable');
    }
    final http.StreamedResponse response;
    try {
      response = await _inner.send(request).timeout(requestTimeout);
    } on TimeoutException {
      throw http.ClientException('Map tile request timed out');
    } catch (_) {
      // An exception can contain its URI. Keep possible provider credentials
      // and sensitive map paths out of logs and Flutter error diagnostics.
      throw http.ClientException('Map tile request failed');
    }
    if (response.statusCode == 429 || response.statusCode == 503) {
      final header = response.headers['retry-after'];
      DateTime? deadline;
      if (header != null) {
        final seconds = int.tryParse(header.trim());
        if (seconds != null && seconds >= 0) {
          // Very long valid pauses saturate rather than overflowing Duration.
          deadline = seconds > 315360000
              ? DateTime.utc(9999, 12, 31)
              : now.add(Duration(seconds: seconds));
        } else {
          try {
            deadline = HttpDate.parse(header).toUtc();
          } on HttpException {
            // The server's invalid/missing deadline falls back to a short pause.
          }
        }
      }
      final minimum = now.add(const Duration(seconds: 30));
      final pause = deadline != null && deadline.isAfter(minimum)
          ? deadline
          : minimum;
      final previous = _state._retryAfter[origin];
      _state._retryAfter[origin] = previous != null && previous.isAfter(pause)
          ? previous
          : pause;
    }
    return http.StreamedResponse(
      response.stream.timeout(requestTimeout),
      response.statusCode,
      contentLength: response.contentLength,
      request: response.request,
      headers: response.headers,
      isRedirect: response.isRedirect,
      persistentConnection: response.persistentConnection,
      reasonPhrase: response.reasonPhrase,
    );
  }

  @override
  void close() {
    _loadedProtectedHeaders = null;
    _inner.close();
  }
}
