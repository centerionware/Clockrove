import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

class ArchivedJob {
  const ArchivedJob(this.id, this.name, this.since, this.canDelete);
  final String id, name;
  final int? since;
  final bool canDelete;
}

class ArchiveData {
  const ArchiveData(this.version, this.jobs);
  final int version;
  final List<ArchivedJob> jobs;
}

class LifecycleApi {
  const LifecycleApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<ArchiveData> archived() async {
    final v = (await _channel.invokeMapMethod<Object?, Object?>(
      'archivedJobs',
    ))!;
    return ArchiveData(
      v['version'] as int,
      (v['jobs'] as List).map((raw) {
        final j = raw as Map<Object?, Object?>;
        return ArchivedJob(
          j['id'] as String,
          j['name'] as String,
          j['archivedMillis'] as int?,
          j['canDelete'] as bool,
        );
      }).toList(),
    );
  }

  Future<TimerOutcome> _change(
    String method,
    String id,
    int version,
    String jobId,
  ) async {
    final v = (await _channel.invokeMapMethod<String, Object?>(method, {
      'id': id,
      'version': version,
      'value': {'jobId': jobId},
    }))!;
    return TimerOutcome(v['version'] as int, v['conflict'] as String?);
  }

  Future<TimerOutcome> archive(String id, int version, String job) =>
      _change('archiveJob', id, version, job);
  Future<TimerOutcome> restore(String id, int version, String job) =>
      _change('restoreJob', id, version, job);
  Future<TimerOutcome> delete(String id, int version, String job) =>
      _change('deleteJob', id, version, job);
}

final lifecycleApiProvider = Provider<LifecycleApi>(
  (ref) => const LifecycleApi(),
);
final archiveProvider = FutureProvider<ArchiveData>((ref) {
  ref.watch(timerVersionsProvider);
  return ref.watch(lifecycleApiProvider).archived();
});
