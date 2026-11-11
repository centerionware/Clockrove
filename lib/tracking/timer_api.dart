import 'package:flutter/services.dart';

enum TimerAction { start, stop, switchJob }

class JobSummary {
  const JobSummary(this.id, this.name);
  final String id;
  final String name;
}

class RunningSession {
  const RunningSession(this.id, this.jobId, this.jobName, this.startUtc);
  final String id;
  final String jobId;
  final String jobName;
  final DateTime startUtc;
}

class TimerSnapshot {
  const TimerSnapshot(this.version, this.jobs, this.running);
  final int version;
  final List<JobSummary> jobs;
  final RunningSession? running;
}

class TimerOutcome {
  const TimerOutcome(this.version, this.conflict);
  final int version;
  final String? conflict;
  bool get committed => conflict == null;
}

/// Typed public contract. Only this adapter sees native transport maps.
/// Re-query snapshot on reconnect/resume; displayed state never authorizes writes.
class TimerApi {
  const TimerApi();
  static const _channel = MethodChannel('clockrove/timer/v1');

  Future<TimerSnapshot> snapshot() async {
    final raw = await _channel.invokeMapMethod<String, Object?>('snapshot');
    if (raw == null) throw const FormatException('Missing timer snapshot');
    final running = raw['running'] as Map<Object?, Object?>?;
    final jobs = raw['jobs'] as List<Object?>;
    return TimerSnapshot(
      raw['version'] as int,
      List.unmodifiable(
        jobs.map((value) {
          final job = value as Map<Object?, Object?>;
          return JobSummary(job['id'] as String, job['name'] as String);
        }),
      ),
      running == null
          ? null
          : RunningSession(
              running['id'] as String,
              running['jobId'] as String,
              running['jobName'] as String,
              DateTime.fromMillisecondsSinceEpoch(
                running['startUtcMillis'] as int,
                isUtc: true,
              ),
            ),
    );
  }

  Future<String> createJob(String name) async {
    final id = await _channel.invokeMethod<String>('createJob', {'name': name});
    if (id == null) throw const FormatException('Missing job ID');
    return id;
  }

  /// Retain id, version, action and jobId for retries of the same user intent.
  /// Android captures and persists the command time and IANA timezone once.
  Future<TimerOutcome> execute({
    required String id,
    required int version,
    required TimerAction action,
    String? jobId,
  }) async {
    final raw = await _channel.invokeMapMethod<String, Object?>('execute', {
      'id': id,
      'version': version,
      'action': switch (action) {
        TimerAction.start => 'START',
        TimerAction.stop => 'STOP',
        TimerAction.switchJob => 'SWITCH',
      },
      'jobId': jobId,
    });
    if (raw == null) throw const FormatException('Missing command outcome');
    return TimerOutcome(raw['version'] as int, raw['conflict'] as String?);
  }
}
