import 'dart:math';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

String newAutomationId() {
  final random = Random.secure();
  return List.generate(
    16,
    (_) => random.nextInt(256),
  ).map((v) => v.toRadixString(16).padLeft(2, '0')).join();
}

class WorkSite {
  const WorkSite(
    this.id,
    this.name,
    this.latitude,
    this.longitude,
    this.radius,
    this.enabled,
  );
  final String id;
  final String name;
  final double latitude;
  final double longitude;
  final double radius;
  final bool enabled;
  factory WorkSite.decode(Map<Object?, Object?> raw) => WorkSite(
    raw['id'] as String,
    raw['name'] as String,
    (raw['latitude'] as num).toDouble(),
    (raw['longitude'] as num).toDouble(),
    (raw['radius'] as num).toDouble(),
    raw['enabled'] as bool,
  );
}

class SitePosition {
  const SitePosition(
    this.latitude,
    this.longitude,
    this.accuracy,
    this.measuredUtc,
  );
  final double latitude;
  final double longitude;
  final double accuracy;
  final DateTime measuredUtc;
}

class SiteSettings {
  const SiteSettings({
    this.enabled = false,
    this.autoStart = false,
    this.automaticDeparture = true,
    this.graceMinutes = 30,
  });
  final bool enabled;
  final bool autoStart;
  final bool automaticDeparture;
  final int graceMinutes;
  SiteSettings copyWith({
    bool? enabled,
    bool? autoStart,
    bool? automaticDeparture,
    int? graceMinutes,
  }) => SiteSettings(
    enabled: enabled ?? this.enabled,
    autoStart: autoStart ?? this.autoStart,
    automaticDeparture: automaticDeparture ?? this.automaticDeparture,
    graceMinutes: graceMinutes ?? this.graceMinutes,
  );
}

class WorkReview {
  const WorkReview(this.sessionId, this.start, this.end, this.historical);
  final String sessionId;
  final DateTime start;
  final DateTime? end;
  final bool historical;
}

class AutomationSnapshot {
  const AutomationSnapshot({
    required this.version,
    required this.settings,
    required this.sites,
    required this.precise,
    required this.background,
    required this.backgroundLabel,
    required this.health,
    required this.armed,
    required this.suppressed,
    this.working = false,
    this.departed,
    this.deadline,
    this.reviews = const [],
  });
  final int version;
  final SiteSettings settings;
  final List<WorkSite> sites;
  final bool precise;
  final bool background;
  final String backgroundLabel;
  final String health;
  final bool armed;
  final bool suppressed;
  final bool working;
  final DateTime? departed;
  final DateTime? deadline;
  final List<WorkReview> reviews;
  static DateTime _time(Object? millis) =>
      DateTime.fromMillisecondsSinceEpoch(millis as int, isUtc: true);
  factory AutomationSnapshot.decode(Map<Object?, Object?> raw) {
    final pending = raw['pending'] as Map<Object?, Object?>?;
    return AutomationSnapshot(
      version: raw['version'] as int,
      settings: SiteSettings(
        enabled: raw['enabled'] as bool,
        autoStart: raw['autoStart'] as bool,
        automaticDeparture: raw['automaticDeparture'] as bool,
        graceMinutes: raw['graceMinutes'] as int,
      ),
      sites: (raw['sites'] as List)
          .map((v) => WorkSite.decode(v as Map<Object?, Object?>))
          .toList(growable: false),
      precise: raw['precise'] as bool,
      background: raw['background'] as bool,
      backgroundLabel: raw['backgroundLabel'] as String,
      health: raw['health'] as String,
      armed: raw['armed'] as bool,
      suppressed: raw['suppressed'] as bool,
      working: raw['working'] as bool,
      departed: pending == null ? null : _time(pending['detected']),
      deadline: pending == null ? null : _time(pending['deadline']),
      reviews: (raw['reviews'] as List)
          .map((value) {
            final v = value as Map<Object?, Object?>;
            return WorkReview(
              v['sessionId'] as String,
              _time(v['start']),
              v['end'] == null ? null : _time(v['end']),
              v['historical'] as bool,
            );
          })
          .toList(growable: false),
    );
  }
}

class AutomationApi {
  const AutomationApi();
  static const _channel = MethodChannel('clockrove/automation/v1');
  static const _health = EventChannel('clockrove/automation/health/v1');
  Stream<String> get health => _health.receiveBroadcastStream().cast<String>();
  Future<AutomationSnapshot> snapshot(String jobId) async {
    final raw = await _channel.invokeMapMethod<Object?, Object?>('snapshot', {
      'jobId': jobId,
    });
    if (raw == null) throw const FormatException('Missing work site snapshot');
    return AutomationSnapshot.decode(raw);
  }

  Future<SitePosition> currentPosition() async {
    final raw = await _channel.invokeMapMethod<String, Object?>(
      'currentPosition',
      {'purposeAccepted': true},
    );
    if (raw == null) throw const FormatException('Missing site position');
    return SitePosition(
      (raw['latitude'] as num).toDouble(),
      (raw['longitude'] as num).toDouble(),
      (raw['accuracy'] as num).toDouble(),
      DateTime.fromMillisecondsSinceEpoch(raw['measured'] as int, isUtc: true),
    );
  }

  Future<void> cancelPosition() async {
    await _channel.invokeMethod<void>('cancelPosition');
  }

  Future<void> retryMonitoring() async {
    await _channel.invokeMethod<void>('retryMonitoring');
  }

  Future<void> requestPrecise() => _permission('requestPrecise');
  Future<void> requestBackground() => _permission('requestBackground');
  Future<void> openSettings() => _permission('openSettings');
  Future<void> _permission(String method) async {
    await _channel.invokeMethod<Object?>(method, {'purposeAccepted': true});
  }

  Future<TimerOutcome> saveSite(
    String id,
    int version,
    String jobId,
    WorkSite site,
  ) => _write('saveSite', {
    'id': id,
    'version': version,
    'jobId': jobId,
    'siteId': site.id,
    'name': site.name,
    'latitude': site.latitude,
    'longitude': site.longitude,
    'radius': site.radius,
    'enabled': site.enabled,
  });
  Future<TimerOutcome> removeSite(String id, int version, String siteId) =>
      _write('removeSite', {'id': id, 'version': version, 'siteId': siteId});
  Future<TimerOutcome> saveSettings(
    String id,
    int version,
    String jobId,
    SiteSettings value,
  ) => _write('saveSettings', {
    'id': id,
    'version': version,
    'jobId': jobId,
    'enabled': value.enabled,
    'autoStart': value.autoStart,
    'automaticDeparture': value.automaticDeparture,
    'graceMinutes': value.graceMinutes,
  });
  Future<TimerOutcome> acceptReview(String id, int version, String jobId) =>
      _write('acceptReview', {'id': id, 'version': version, 'jobId': jobId});
  Future<TimerOutcome> _write(
    String method,
    Map<String, Object?> values,
  ) async {
    final raw = await _channel.invokeMapMethod<String, Object?>(method, values);
    if (raw == null) throw const FormatException('Missing work site outcome');
    return TimerOutcome(raw['version'] as int, raw['conflict'] as String?);
  }
}

final automationApiProvider = Provider<AutomationApi>(
  (ref) => const AutomationApi(),
);
final automationHealthProvider = StreamProvider<String>(
  (ref) => ref.watch(automationApiProvider).health,
);
final automationSnapshotProvider =
    FutureProvider.family<AutomationSnapshot, String>((ref, jobId) {
      ref.watch(timerVersionsProvider);
      ref.watch(automationHealthProvider);
      return ref.watch(automationApiProvider).snapshot(jobId);
    });
