import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'timer_providers.dart';

class TrackingStatus {
  const TrackingStatus(
    this.permission,
    this.service,
    this.working,
    this.notificationsAllowed, {
    this.lastObservationUtc,
    this.lastAccuracyMeters,
    this.lastLatitude,
    this.lastLongitude,
  });
  final String permission;
  final String service;
  final bool working;
  final bool notificationsAllowed;
  final DateTime? lastObservationUtc;
  final double? lastAccuracyMeters;
  final double? lastLatitude;
  final double? lastLongitude;
  bool get hasMapObservation =>
      lastObservationUtc != null &&
      lastLatitude != null &&
      lastLatitude!.isFinite &&
      lastLatitude! >= -90 &&
      lastLatitude! <= 90 &&
      lastLongitude != null &&
      lastLongitude!.isFinite &&
      lastLongitude! >= -180 &&
      lastLongitude! <= 180;
}

class TrackingApi {
  const TrackingApi();
  static const _channel = MethodChannel('clockrove/tracking/v1');
  static const _health = EventChannel('clockrove/tracking/health/v1');
  Stream<String> get health => _health.receiveBroadcastStream().cast<String>();

  Future<TrackingStatus> status() => _invoke('status');
  Future<TrackingStatus> enable() => _invoke('enable');
  Future<TrackingStatus> disable() => _invoke('disable');
  Future<TrackingStatus> requestNotifications() =>
      _invoke('requestNotifications');

  Future<TrackingStatus> _invoke(String method) async {
    final raw = await _channel.invokeMapMethod<String, Object?>(method);
    if (raw == null) throw const FormatException('Missing tracking status');
    return TrackingStatus(
      raw['permission'] as String,
      raw['service'] as String,
      raw['working'] as bool,
      raw['notificationsAllowed'] as bool,
      lastObservationUtc: raw['lastMeasuredMillis'] == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(
              raw['lastMeasuredMillis'] as int,
              isUtc: true,
            ),
      lastAccuracyMeters: (raw['lastAccuracyMeters'] as num?)?.toDouble(),
      lastLatitude: (raw['lastLatitude'] as num?)?.toDouble(),
      lastLongitude: (raw['lastLongitude'] as num?)?.toDouble(),
    );
  }
}

final trackingApiProvider = Provider<TrackingApi>((ref) => const TrackingApi());
final trackingHealthProvider = StreamProvider<String>(
  (ref) => ref.watch(trackingApiProvider).health,
);
final trackingStatusProvider = FutureProvider<TrackingStatus>((ref) {
  ref.watch(trackingHealthProvider);
  ref.watch(timerVersionsProvider);
  return ref.watch(trackingApiProvider).status();
});
