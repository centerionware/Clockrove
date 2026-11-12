import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

class LegacyCoveredTime {
  const LegacyCoveredTime({
    required this.start,
    required this.end,
    required this.timezone,
    this.startLabel = '',
    this.endLabel = '',
    this.localStart = '',
    this.localEnd = '',
  });
  final int start, end;
  final String timezone, startLabel, endLabel, localStart, localEnd;
  Map<String, Object?> encode() => {
    'start': start,
    'end': end,
    'timezone': timezone,
  };
}

class LegacyInvoiceDraft {
  LegacyInvoiceDraft({
    required this.invoiceId,
    required List<String> tasks,
    required List<LegacyCoveredTime> times,
    required this.note,
    required this.confirmed,
    required this.noSources,
  }) : tasks = List.unmodifiable(tasks),
       times = List.unmodifiable(times);
  final String invoiceId, note;
  final List<String> tasks;
  final List<LegacyCoveredTime> times;
  final bool confirmed, noSources;
  Map<String, Object?> encode() => {
    'invoiceId': invoiceId,
    'taskIds': tasks,
    'spans': times.map((value) => value.encode()).toList(),
    'note': note,
    'confirmed': confirmed,
    'noSources': noSources,
  };
}

class LegacyInvoiceTask {
  const LegacyInvoiceTask(
    this.id,
    this.title,
    this.completedLabel,
    this.invoiceId,
    this.number,
  );
  final String id, title;
  final String? completedLabel, invoiceId, number;
}

class LegacyReservedTime {
  const LegacyReservedTime(this.time, this.invoiceId, this.number);
  final LegacyCoveredTime time;
  final String invoiceId, number;
}

class LegacyInvoiceContext {
  const LegacyInvoiceContext({
    required this.version,
    required this.invoiceId,
    required this.number,
    required this.jobId,
    required this.jobName,
    required this.total,
    required this.currency,
    required this.period,
    required this.timezone,
    required this.legacy,
    required this.jobDeleted,
    required this.tasks,
    required this.reservedTimes,
    required this.note,
    this.reviewed,
  });
  final int version;
  final int? reviewed;
  final String invoiceId,
      number,
      jobId,
      jobName,
      total,
      currency,
      period,
      timezone,
      note;
  final bool legacy, jobDeleted;
  final List<LegacyInvoiceTask> tasks;
  final List<LegacyReservedTime> reservedTimes;
  factory LegacyInvoiceContext.decode(Map<Object?, Object?> value) =>
      LegacyInvoiceContext(
        version: value['version'] as int,
        invoiceId: value['invoiceId'] as String,
        number: value['number'] as String,
        jobId: value['jobId'] as String,
        jobName: value['jobName'] as String,
        total: value['total'] as String,
        currency: value['currency'] as String,
        period: value['period'] as String,
        timezone: value['timezone'] as String,
        legacy: value['legacy'] as bool,
        jobDeleted: value['jobDeleted'] as bool,
        note: value['note'] as String,
        reviewed: value['reviewed'] as int?,
        tasks: (value['tasks'] as List).map((raw) {
          final task = raw as Map<Object?, Object?>;
          return LegacyInvoiceTask(
            task['id'] as String,
            task['title'] as String,
            task['completedLabel'] as String?,
            task['invoiceId'] as String?,
            task['number'] as String?,
          );
        }).toList(),
        reservedTimes: (value['reservedTimes'] as List).map((raw) {
          final time = raw as Map<Object?, Object?>;
          return LegacyReservedTime(
            LegacyCoveredTime(
              start: time['start'] as int,
              end: time['end'] as int,
              timezone: time['timezone'] as String,
              startLabel: time['startLabel'] as String,
              endLabel: time['endLabel'] as String,
            ),
            time['invoiceId'] as String,
            time['number'] as String,
          );
        }).toList(),
      );
}

class LegacyInvoiceApi {
  const LegacyInvoiceApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<LegacyInvoiceContext> context(String invoiceId) async =>
      LegacyInvoiceContext.decode(
        (await _channel.invokeMapMethod<Object?, Object?>(
          'legacyInvoiceContext',
          {'invoiceId': invoiceId},
        ))!,
      );
  Future<TimerOutcome> reconcile(
    String id,
    int version,
    LegacyInvoiceDraft draft,
  ) async {
    final result = (await _channel.invokeMapMethod<String, Object?>(
      'reconcileInvoiceCoverage',
      {'id': id, 'version': version, 'value': draft.encode()},
    ))!;
    return TimerOutcome(
      result['version'] as int,
      result['conflict'] as String?,
    );
  }
}

final legacyInvoiceApiProvider = Provider<LegacyInvoiceApi>(
  (ref) => const LegacyInvoiceApi(),
);
final legacyInvoiceContextProvider =
    FutureProvider.family<LegacyInvoiceContext, String>((ref, invoiceId) {
      ref.watch(timerVersionsProvider);
      return ref.watch(legacyInvoiceApiProvider).context(invoiceId);
    });
