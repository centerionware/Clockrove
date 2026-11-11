import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'timer_api.dart';

class HistoryCursor {
  const HistoryCursor(this.start, this.id);
  final int start;
  final String id;
  Map<String, Object?> encode() => {'start': start, 'id': id};
}

class HistoryEntry {
  const HistoryEntry({
    required this.id,
    required this.jobId,
    required this.start,
    required this.end,
    required this.timezone,
    required this.offsetSeconds,
    required this.origin,
    required this.needsReview,
  });
  final String id, jobId, timezone, origin;
  final int start, offsetSeconds;
  final int? end;
  final bool needsReview;
  factory HistoryEntry.decode(Map<Object?, Object?> value) => HistoryEntry(
    id: value['id'] as String,
    jobId: value['jobId'] as String,
    start: value['start'] as int,
    end: value['end'] as int?,
    timezone: value['timezone'] as String,
    offsetSeconds: value['offsetSeconds'] as int,
    origin: value['origin'] as String,
    needsReview: value['needsReview'] as bool,
  );
}

class HistoryData {
  HistoryData(this.version, List<HistoryEntry> entries, this.next)
    : entries = List.unmodifiable(entries);
  final int version;
  final List<HistoryEntry> entries;
  final HistoryCursor? next;
}

/// UTC millisecond bounds and the timezone offset at the start instant. Forms
/// must resolve local-time ambiguity before constructing this immutable draft.
class HistoryDraft {
  const HistoryDraft({
    required this.sessionId,
    required this.jobId,
    required this.start,
    required this.end,
    required this.timezone,
    required this.offsetSeconds,
  });
  final String sessionId, jobId, timezone;
  final int start, end, offsetSeconds;
  Map<String, Object?> encode() => {
    'sessionId': sessionId,
    'jobId': jobId,
    'start': start,
    'end': end,
    'timezone': timezone,
    'offsetSeconds': offsetSeconds,
  };
}

class HistoryRevision {
  HistoryRevision({
    required this.id,
    required this.changed,
    required this.origin,
    required this.deleted,
    required this.payload,
    required List<String> parents,
  }) : parents = List.unmodifiable(parents);
  final String id, origin;
  final int changed;
  final bool deleted;
  final String? payload;
  final List<String> parents;
}

class HistoryTimeChoice {
  const HistoryTimeChoice(this.instant, this.offsetSeconds, this.label);
  final int instant, offsetSeconds;
  final String label;
}

class HistoryApi {
  const HistoryApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<List<HistoryTimeChoice>> resolve(
    String localTime,
    String timezone,
  ) async {
    final values = (await _channel.invokeListMethod<Object?>(
      'resolveSessionTime',
      {'localTime': localTime, 'timezone': timezone},
    ))!;
    return List.unmodifiable(values.map((raw) {
      final value = raw as Map<Object?, Object?>;
      return HistoryTimeChoice(
        value['instant'] as int,
        value['offsetSeconds'] as int,
        value['label'] as String,
      );
    }));
  }

  Future<HistoryData> history(
    String jobId, {
    HistoryCursor? before,
    int limit = 200,
  }) async {
    final value = (await _channel.invokeMapMethod<Object?, Object?>(
      'sessionHistory',
      {'jobId': jobId, 'before': before?.encode(), 'limit': limit},
    ))!;
    final cursor = value['next'] as Map<Object?, Object?>?;
    return HistoryData(
      value['version'] as int,
      (value['entries'] as List)
          .map((raw) => HistoryEntry.decode(raw as Map<Object?, Object?>))
          .toList(),
      cursor == null
          ? null
          : HistoryCursor(cursor['start'] as int, cursor['id'] as String),
    );
  }

  Future<List<HistoryRevision>> revisions(
    String jobId,
    String sessionId,
  ) async {
    final values = (await _channel.invokeListMethod<Object?>(
      'sessionRevisions',
      {'jobId': jobId, 'sessionId': sessionId},
    ))!;
    return List.unmodifiable(values.map((raw) {
      final value = raw as Map<Object?, Object?>;
      return HistoryRevision(
        id: value['id'] as String,
        changed: value['changed'] as int,
        origin: value['origin'] as String,
        deleted: value['deleted'] as bool,
        payload: value['payload'] as String?,
        parents: (value['parents'] as List).cast<String>(),
      );
    }));
  }

  Future<TimerOutcome> _change(
    String method,
    String id,
    int version,
    Map<String, Object?> value,
  ) async {
    final outcome = (await _channel.invokeMapMethod<String, Object?>(method, {
      'id': id,
      'version': version,
      'value': value,
    }))!;
    return TimerOutcome(
      outcome['version'] as int,
      outcome['conflict'] as String?,
    );
  }

  Future<TimerOutcome> create(String id, int version, HistoryDraft draft) =>
      _change('createManualSession', id, version, draft.encode());
  Future<TimerOutcome> correct(String id, int version, HistoryDraft draft) =>
      _change('correctSession', id, version, draft.encode());
  Future<TimerOutcome> delete(String id, int version, String sessionId) =>
      _change('deleteSession', id, version, {'sessionId': sessionId});
}

final historyApiProvider = Provider<HistoryApi>((ref) => const HistoryApi());
