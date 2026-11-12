import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import 'quote_api.dart';

class QuoteScopeBinding {
  QuoteScopeBinding(Map<Object?, Object?> value)
    : lineId = value['lineId'] as String,
      jobId = value['jobId'] as String,
      taskId = value['taskId'] as String,
      kind = value['kind'] as String,
      originalRevision = value['originalRevision'] as int;
  final String lineId, jobId, taskId, kind;
  final int originalRevision;
}

class QuoteScopeJob {
  QuoteScopeJob(Map<Object?, Object?> value)
    : id = value['jobId'] as String,
      name = value['name'] as String,
      mode = value['mode'] as String,
      currency = value['currency'] as String,
      archived = value['archived'] as bool;
  final String id, name, mode, currency;
  final bool archived;
}

class QuoteScopeTarget extends QuoteScopeJob {
  QuoteScopeTarget(super.value)
    : digits = value['digits'] as int,
      taxUnits = value['taxUnits'] as int,
      customerId = value['customerId'] as String?,
      customerName = value['customerName'] as String?,
      rate = value['hourlyMinor'] as String?;
  final int digits, taxUnits;
  final String? customerId, customerName, rate;
}

class QuoteScopeTask {
  QuoteScopeTask(Map<Object?, Object?> value)
    : id = value['taskId'] as String,
      title = value['title'] as String,
      completed = value['completed'] as int?,
      billable = value['billable'] as bool,
      price = value['unitPriceMinor'] as String?,
      quantity = value['quantityUnits'] as String,
      ownerQuoteId = value['ownerQuoteId'] as String?,
      ownerLineId = value['ownerLineId'] as String?;
  final String id, title, quantity;
  final String? price, ownerQuoteId, ownerLineId;
  final int? completed;
  final bool billable;
}

class QuoteScopeGroup {
  QuoteScopeGroup(this.key, this.kind, this.rate, List<QuoteLine> lines)
    : lines = List.unmodifiable(lines);
  final String key, kind;
  final String? rate;
  final List<QuoteLine> lines;
  String get mode => kind == 'HOURS' ? 'HOURLY' : 'TASK';
}

/// One job has one billing mode/rate. Every quote line remains visible in its
/// compatible group; mixed estimates are never silently reduced to one kind.
List<QuoteScopeGroup> quoteScopeGroups(QuoteDraft draft) {
  final grouped = <String, List<QuoteLine>>{};
  for (final line in draft.lines) {
    if (line.kind != 'ITEM' && line.kind != 'HOURS') {
      throw const FormatException(
        'Unsupported quote line type. Refresh the issued revision.',
      );
    }
    final key = line.kind == 'ITEM' ? 'ITEM' : 'HOURS:${line.unitPrice}';
    grouped.putIfAbsent(key, () => []).add(line);
  }
  return List.unmodifiable(
    grouped.entries.map(
      (entry) => QuoteScopeGroup(
        entry.key,
        entry.value.first.kind,
        entry.value.first.kind == 'HOURS' ? entry.value.first.unitPrice : null,
        entry.value,
      ),
    ),
  );
}

class QuoteScopeData {
  QuoteScopeData(Map<Object?, Object?> value)
    : version = value['version'] as int,
      issued = QuoteRevision(value['issued'] as Map<Object?, Object?>),
      latestRevision = value['latestRevision'] as int,
      linkedLines = Set.unmodifiable(
        (value['linkedLines'] as List).cast<String>(),
      ),
      bindings = List.unmodifiable(
        (value['bindings'] as List).map(
          (v) => QuoteScopeBinding(v as Map<Object?, Object?>),
        ),
      ),
      jobs = List.unmodifiable(
        (value['jobs'] as List).map(
          (v) => QuoteScopeJob(v as Map<Object?, Object?>),
        ),
      ),
      target = value['target'] == null
          ? null
          : QuoteScopeTarget(value['target'] as Map<Object?, Object?>),
      tasks = List.unmodifiable(
        (value['tasks'] as List).map(
          (v) => QuoteScopeTask(v as Map<Object?, Object?>),
        ),
      );
  final int version, latestRevision;
  final QuoteRevision issued;
  final Set<String> linkedLines;
  final List<QuoteScopeBinding> bindings;
  final List<QuoteScopeJob> jobs;
  final QuoteScopeTarget? target;
  final List<QuoteScopeTask> tasks;
  bool get historical => issued.revision != latestRevision;
}

class QuoteScopeItem {
  const QuoteScopeItem(
    this.lineId,
    this.taskId,
    this.title, {
    this.existing = false,
  });
  final String lineId, taskId, title;
  final bool existing;
  Map<String, Object?> encode() => {
    'lineId': lineId,
    'taskId': taskId,
    'title': title,
    'existing': existing,
  };
}

class QuoteScopeDraft {
  QuoteScopeDraft({
    required this.quoteId,
    required this.revision,
    required this.jobId,
    required List<QuoteScopeItem> items,
    required this.note,
    this.newJobName,
    this.historicalReviewed = false,
    this.jobDifferencesReviewed = false,
  }) : items = List.unmodifiable(items);
  final String quoteId, jobId, note;
  final int revision;
  final String? newJobName;
  final bool historicalReviewed, jobDifferencesReviewed;
  final List<QuoteScopeItem> items;
  Map<String, Object?> encode() => {
    'quoteId': quoteId,
    'revision': revision,
    'jobId': jobId,
    'items': items.map((item) => item.encode()).toList(),
    'note': note,
    'newJobName': newJobName,
    'confirmed': true,
    'historicalReviewed': historicalReviewed,
    'jobDifferencesReviewed': jobDifferencesReviewed,
  };
}

class QuoteScopeApi {
  const QuoteScopeApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<QuoteScopeData> read(
    String quoteId,
    int revision, {
    String? jobId,
  }) async => QuoteScopeData(
    (await _channel.invokeMapMethod<Object?, Object?>('quoteScopeContext', {
      'quoteId': quoteId,
      'revision': revision,
      'jobId': jobId,
    }))!,
  );
  Future<TimerOutcome> apply(
    String id,
    int version,
    QuoteScopeDraft draft,
  ) async {
    final value = (await _channel.invokeMapMethod<Object?, Object?>(
      'applyQuoteScope',
      {'id': id, 'version': version, 'value': draft.encode()},
    ))!;
    return TimerOutcome(value['version'] as int, value['conflict'] as String?);
  }
}

final quoteScopeApiProvider = Provider<QuoteScopeApi>(
  (ref) => const QuoteScopeApi(),
);
