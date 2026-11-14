import 'dart:math';

import 'package:decimal/decimal.dart';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

String newBusinessId() => List.generate(
  16,
  (_) => Random.secure().nextInt(256),
).map((v) => v.toRadixString(16).padLeft(2, '0')).join();

/// Decimal input and display never pass through floating point.
String? parseMoney(String input, int digits) =>
    _parseMoney(input, digits, 1024);

/// Extended invoice balances can exceed the source-price text limit.
String? parsePaymentMoney(String input, int digits) =>
    _parseMoney(input, digits, 4096);

String? _parseMoney(String input, int digits, int maxDigits) {
  final entered = input.trim();
  if (entered.isEmpty) return null;
  if (entered.length > maxDigits + 5) {
    throw const FormatException('Amount is too long.');
  }
  // Accept the common currency shorthand `.01` as `0.01`, without changing
  // the decimal parser or passing the amount through floating-point math.
  final value = entered.startsWith('.') ? '0$entered' : entered;
  if (!RegExp(r'^\d+(\.\d+)?$').hasMatch(value)) {
    throw const FormatException(
      'Enter a positive decimal amount, or leave blank.',
    );
  }
  final parts = value.split('.');
  final fraction = parts.length == 2 ? parts[1] : '';
  if (fraction.length > digits) {
    throw FormatException('This currency allows $digits decimal places.');
  }
  // Precision was validated above: shifting produces an exact integer, so
  // toBigInt cannot discard a fractional minor unit.
  final minor = Decimal.parse(value).shift(digits).toBigInt();
  if (minor.toString().length > maxDigits) {
    throw const FormatException('Amount is too long.');
  }
  return minor.toString();
}

String money(String? minor, int digits) => minor == null
    ? ''
    : Decimal.parse(minor).shift(-digits).toStringAsFixed(digits);

/// Quantities use four exact decimal places, with one represented as 10000.
String parseTaskQuantity(String input) {
  final value = input.trim();
  if (!RegExp(r'^\d+(\.\d{1,4})?$').hasMatch(value)) {
    throw const FormatException(
      'Enter a quantity greater than 0, with up to 4 decimal places.',
    );
  }
  final parts = value.split('.');
  final units =
      BigInt.parse(parts[0]) * BigInt.from(10000) +
      BigInt.parse((parts.length == 2 ? parts[1] : '').padRight(4, '0'));
  if (units <= BigInt.zero || units > BigInt.parse('9223372036854775807')) {
    throw const FormatException(
      'Quantity must be greater than 0 and within the supported range.',
    );
  }
  return units.toString();
}

String taskQuantity(String units) => money(
  units,
  4,
).replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '');

/// Exact decimal product; HALF_UP happens once, at the task's minor-unit boundary.
String? taskAmount(String? unitPriceMinor, String quantityUnits) =>
    unitPriceMinor == null
    ? null
    : (Decimal.parse(unitPriceMinor) * Decimal.parse(quantityUnits).shift(-4))
          .round()
          .toBigInt()
          .toString();

class BoardColumn {
  const BoardColumn(this.id, this.name, this.completed);
  final String id, name;
  final bool completed;
  factory BoardColumn.decode(Map<Object?, Object?> v) => BoardColumn(
    v['id'] as String,
    v['name'] as String,
    v['completed'] as bool,
  );
}

class TaskCard {
  const TaskCard({
    required this.id,
    required this.columnId,
    required this.title,
    required this.description,
    required this.position,
    required this.billable,
    required this.currency,
    this.digits = 2,
    this.dueDate,
    this.priceMinor,
    this.completedMillis,
    this.quantityUnits = '10000',
    this.chargeType = 'UNCLASSIFIED',
  });
  final String id,
      columnId,
      title,
      description,
      currency,
      quantityUnits,
      chargeType;
  String? get amountMinor => taskAmount(priceMinor, quantityUnits);
  final String? dueDate, priceMinor;
  final int position;
  final int? completedMillis;
  final bool billable;
  factory TaskCard.decode(Map<Object?, Object?> v) => TaskCard(
    id: v['id'] as String,
    columnId: v['columnId'] as String,
    title: v['title'] as String,
    description: v['description'] as String,
    position: v['position'] as int,
    billable: v['billable'] as bool,
    currency: v['currency'] as String,
    dueDate: v['dueDate'] as String?,
    priceMinor: v['priceMinor'] as String?,
    completedMillis: v['completedMillis'] as int?,
    quantityUnits: v['quantityUnits'] as String? ?? '10000',
    chargeType: v['chargeType'] as String? ?? 'UNCLASSIFIED',
  );
}

class TaskTemplate {
  const TaskTemplate(
    this.id,
    this.title,
    this.description,
    this.priceMinor,
    this.currency,
    this.source, [
    this.quantityUnits = '10000',
    this.chargeType = 'UNCLASSIFIED',
  ]);
  final String id, title, description, currency, quantityUnits, chargeType;
  final String? priceMinor, source;
  factory TaskTemplate.decode(Map<Object?, Object?> v) => TaskTemplate(
    v['id'] as String,
    v['title'] as String,
    v['description'] as String,
    v['priceMinor'] as String?,
    v['currency'] as String,
    v['sourceJobName'] as String?,
    v['quantityUnits'] as String? ?? '10000',
    v['chargeType'] as String? ?? 'UNCLASSIFIED',
  );
}

class TaskAttachmentInfo {
  const TaskAttachmentInfo({
    required this.id,
    required this.createdMillis,
    required this.bytes,
    this.commentId,
  });
  final String id;
  final String? commentId;
  final int createdMillis, bytes;
  factory TaskAttachmentInfo.decode(Map<Object?, Object?> value) =>
      TaskAttachmentInfo(
        id: value['id'] as String,
        commentId: value['commentId'] as String?,
        createdMillis: value['created'] as int,
        bytes: value['bytes'] as int,
      );
}

class TaskComment {
  const TaskComment({
    required this.id,
    required this.body,
    required this.createdMillis,
    required this.updatedMillis,
    required this.attachments,
  });
  final String id, body;
  final int createdMillis, updatedMillis;
  final List<TaskAttachmentInfo> attachments;
  factory TaskComment.decode(Map<Object?, Object?> value) => TaskComment(
    id: value['id'] as String,
    body: value['body'] as String,
    createdMillis: value['created'] as int,
    updatedMillis: value['updated'] as int,
    attachments: (value['attachments'] as List)
        .map((item) => TaskAttachmentInfo.decode(item as Map<Object?, Object?>))
        .toList(),
  );
}

class TaskCommentEvent {
  const TaskCommentEvent(this.commentId, this.action, this.recordedMillis);
  final String commentId, action;
  final int recordedMillis;
  factory TaskCommentEvent.decode(Map<Object?, Object?> value) =>
      TaskCommentEvent(
        value['commentId'] as String,
        value['action'] as String,
        value['recorded'] as int,
      );
}

class TaskConversation {
  const TaskConversation({
    required this.version,
    required this.jobId,
    required this.taskId,
    required this.archived,
    required this.taskAttachments,
    required this.comments,
    required this.events,
  });
  final int version;
  final String jobId, taskId;
  final bool archived;
  final List<TaskAttachmentInfo> taskAttachments;
  final List<TaskComment> comments;
  final List<TaskCommentEvent> events;
  factory TaskConversation.decode(
    Map<Object?, Object?> value,
  ) => TaskConversation(
    version: value['version'] as int,
    jobId: value['jobId'] as String,
    taskId: value['taskId'] as String,
    archived: value['archived'] as bool,
    taskAttachments: (value['taskAttachments'] as List)
        .map((item) => TaskAttachmentInfo.decode(item as Map<Object?, Object?>))
        .toList(),
    comments: (value['comments'] as List)
        .map((item) => TaskComment.decode(item as Map<Object?, Object?>))
        .toList(),
    events: (value['events'] as List)
        .map((item) => TaskCommentEvent.decode(item as Map<Object?, Object?>))
        .toList(),
  );
}

class ActualCostEntry {
  const ActualCostEntry({
    required this.id,
    required this.amountMinor,
    required this.currency,
    this.digits = 2,
    required this.category,
    required this.occurred,
    required this.timezone,
    required this.vendor,
    required this.reference,
    required this.note,
    this.jobId,
    this.jobName = '',
    this.taskId,
    this.taskName = '',
    this.offsetSeconds = 0,
    this.created = 0,
    this.updated = 0,
  });
  final String id, amountMinor, currency, category, timezone;
  final int digits;
  final String vendor, reference, note, jobName, taskName;
  final String? jobId, taskId;
  final int occurred, offsetSeconds, created, updated;
  factory ActualCostEntry.decode(Map<Object?, Object?> value) =>
      ActualCostEntry(
        id: value['id'] as String,
        jobId: value['jobId'] as String?,
        jobName: value['jobName'] as String,
        taskId: value['taskId'] as String?,
        taskName: value['taskName'] as String,
        amountMinor: value['amountMinor'] as String,
        currency: value['currency'] as String,
        digits: value['fractionDigits'] as int? ?? 2,
        category: value['category'] as String,
        occurred: value['occurred'] as int,
        timezone: value['timezone'] as String,
        offsetSeconds: value['offsetSeconds'] as int,
        vendor: value['vendor'] as String,
        reference: value['reference'] as String,
        note: value['note'] as String,
        created: value['created'] as int,
        updated: value['updated'] as int,
      );
}

class ActualCostsSnapshot {
  const ActualCostsSnapshot(this.version, this.entries, this.next);
  final int version;
  final List<ActualCostEntry> entries;
  final ActualCostCursor? next;
  factory ActualCostsSnapshot.decode(Map<Object?, Object?> value) =>
      ActualCostsSnapshot(
        value['version'] as int,
        (value['entries'] as List)
            .map((row) => ActualCostEntry.decode(row as Map<Object?, Object?>))
            .toList(),
        value['next'] == null
            ? null
            : ActualCostCursor.decode(value['next'] as Map<Object?, Object?>),
      );
}

class ActualCostCursor {
  const ActualCostCursor(this.occurred, this.id);
  final int occurred;
  final String id;
  factory ActualCostCursor.decode(Map<Object?, Object?> value) =>
      ActualCostCursor(value['occurred'] as int, value['id'] as String);
  Map<String, Object?> encode() => {'occurred': occurred, 'id': id};
}

class JobBoard {
  const JobBoard(
    this.version,
    this.jobId,
    this.name,
    this.currency,
    this.digits,
    this.archived,
    this.columns,
    this.cards,
  );
  final int version, digits;
  final String jobId, name, currency;
  final bool archived;
  final List<BoardColumn> columns;
  final List<TaskCard> cards;
  factory JobBoard.decode(Map<Object?, Object?> v) => JobBoard(
    v['version'] as int,
    v['jobId'] as String,
    v['name'] as String,
    v['currency'] as String,
    v['fractionDigits'] as int,
    v['archived'] as bool,
    (v['columns'] as List)
        .map((e) => BoardColumn.decode(e as Map<Object?, Object?>))
        .toList(),
    (v['cards'] as List)
        .map((e) => TaskCard.decode(e as Map<Object?, Object?>))
        .toList(),
  );
  List<TaskCard> inColumn(String id) =>
      cards.where((c) => c.columnId == id).toList()
        ..sort((a, b) => a.position.compareTo(b.position));
}

class BusinessApi {
  const BusinessApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<JobBoard> board(String jobId) async => JobBoard.decode(
    (await _channel.invokeMapMethod<Object?, Object?>('board', {
      'jobId': jobId,
    }))!,
  );
  Future<List<TaskTemplate>> templates(String search) async =>
      (await _channel.invokeListMethod<Object?>('templates', {
        'search': search,
      }))!.map((e) => TaskTemplate.decode(e as Map<Object?, Object?>)).toList();
  Future<TaskConversation> taskConversation(
    String jobId,
    String taskId,
  ) async => TaskConversation.decode(
    (await _channel.invokeMapMethod<Object?, Object?>('taskConversation', {
      'jobId': jobId,
      'taskId': taskId,
    }))!,
  );
  Future<Uint8List?> taskAttachment(String attachmentId) async {
    final value = await _channel.invokeMapMethod<Object?, Object?>(
      'taskAttachment',
      {'attachmentId': attachmentId},
    );
    return value?['unavailable'] == true ? null : value?['image'] as Uint8List?;
  }

  Future<ActualCostsSnapshot> actualCosts({
    ActualCostCursor? before,
    int limit = 100,
  }) async => ActualCostsSnapshot.decode(
    (await _channel.invokeMapMethod<Object?, Object?>('actualCosts', {
      'before': before?.encode(),
      'limit': limit,
    }))!,
  );

  Future<int> currencyDigits(String currency) async =>
      ((await _channel.invokeMapMethod<Object?, Object?>('currencyPrecision', {
        'currency': currency,
      }))!['digits'] as int);

  Future<TimerOutcome> _change(
    String method,
    String id,
    int version,
    Map<String, Object?> value,
  ) async {
    final result = (await _channel.invokeMapMethod<String, Object?>(method, {
      'id': id,
      'version': version,
      'value': value,
    }))!;
    return TimerOutcome(
      result['version'] as int,
      result['conflict'] as String?,
    );
  }

  Future<TimerOutcome> saveColumn(
    String id,
    JobBoard b,
    String columnId,
    String name,
    bool completed,
  ) => _change('saveColumn', id, b.version, {
    'jobId': b.jobId,
    'columnId': columnId,
    'name': name,
    'completed': completed,
  });
  Future<TimerOutcome> orderColumns(
    String id,
    JobBoard b,
    List<String> order,
  ) => _change('reorderColumns', id, b.version, {
    'jobId': b.jobId,
    'columnIds': order,
  });
  Future<TimerOutcome> removeColumn(
    String id,
    JobBoard b,
    String columnId,
    String? destination,
  ) => _change('removeColumn', id, b.version, {
    'jobId': b.jobId,
    'columnId': columnId,
    'destinationId': destination,
  });
  Future<TimerOutcome> saveCard(String id, JobBoard b, TaskCard c) =>
      _change('saveCard', id, b.version, {
        'jobId': b.jobId,
        'cardId': c.id,
        'columnId': c.columnId,
        'title': c.title,
        'description': c.description,
        'dueDate': c.dueDate,
        'billable': c.billable,
        'priceMinor': c.priceMinor,
        'quantityUnits': c.quantityUnits,
        'currency': c.currency,
        'chargeType': c.chargeType,
      });
  Future<TimerOutcome> saveTaskComment(
    String commandId,
    TaskConversation conversation,
    String commentId,
    String body,
  ) => _change('saveTaskComment', commandId, conversation.version, {
    'jobId': conversation.jobId,
    'taskId': conversation.taskId,
    'commentId': commentId,
    'body': body,
  });
  Future<TimerOutcome> removeTaskComment(
    String commandId,
    TaskConversation conversation,
    String commentId,
  ) => _change('removeTaskComment', commandId, conversation.version, {
    'jobId': conversation.jobId,
    'taskId': conversation.taskId,
    'commentId': commentId,
  });
  Future<TimerOutcome> attachTaskImage(
    String commandId,
    TaskConversation conversation,
    String attachmentId,
    String? commentId,
    Uint8List bytes,
  ) => _change('attachTaskImage', commandId, conversation.version, {
    'jobId': conversation.jobId,
    'taskId': conversation.taskId,
    'attachmentId': attachmentId,
    'commentId': commentId,
    'image': bytes,
  });
  Future<TimerOutcome> attachTaskImages(
    String commandId,
    TaskConversation conversation,
    List<MapEntry<String, Uint8List>> images,
    String? commentId,
  ) => _change('attachTaskImages', commandId, conversation.version, {
    'jobId': conversation.jobId,
    'taskId': conversation.taskId,
    'commentId': commentId,
    'images': [
      for (final image in images)
        {'attachmentId': image.key, 'image': image.value},
    ],
  });
  Future<TimerOutcome> removeTaskImage(
    String commandId,
    TaskConversation conversation,
    String attachmentId,
  ) => _change('removeTaskImage', commandId, conversation.version, {
    'jobId': conversation.jobId,
    'taskId': conversation.taskId,
    'attachmentId': attachmentId,
  });
  Future<TimerOutcome> saveActualCost(
    String commandId,
    int version,
    ActualCostEntry entry,
  ) => _change('saveActualCost', commandId, version, {
    'costId': entry.id,
    'jobId': entry.jobId,
    'taskId': entry.taskId,
    'amountMinor': entry.amountMinor,
    'currency': entry.currency,
    'category': entry.category,
    'occurred': entry.occurred,
    'timezone': entry.timezone,
    'vendor': entry.vendor,
    'reference': entry.reference,
    'note': entry.note,
  });
  Future<TimerOutcome> removeActualCost(
    String commandId,
    int version,
    String costId,
  ) => _change('removeActualCost', commandId, version, {'costId': costId});
  Future<TimerOutcome> moveCard(
    String id,
    JobBoard b,
    TaskCard c,
    String destination,
    int position,
  ) => _change('moveCard', id, b.version, {
    'jobId': b.jobId,
    'cardId': c.id,
    'destinationId': destination,
    'position': position,
  });
  Future<TimerOutcome> removeCard(String id, JobBoard b, TaskCard c) =>
      _change('removeCard', id, b.version, {'jobId': b.jobId, 'cardId': c.id});
  Future<TimerOutcome> copyTemplate(
    String id,
    JobBoard b,
    TaskTemplate t,
    String cardId,
    String columnId,
    bool price,
  ) => _change('copyTemplate', id, b.version, {
    'jobId': b.jobId,
    'templateId': t.id,
    'cardId': cardId,
    'columnId': columnId,
    'copyPrice': price,
  });
  Future<TimerOutcome> removeTemplate(String id, int version, TaskTemplate t) =>
      _change('removeTemplate', id, version, {'templateId': t.id});
}

final businessApiProvider = Provider<BusinessApi>((ref) => const BusinessApi());
final actualCostsProvider = FutureProvider<ActualCostsSnapshot>((ref) {
  ref.watch(timerVersionsProvider);
  return ref.watch(businessApiProvider).actualCosts();
});
final boardProvider = FutureProvider.family<JobBoard, String>((ref, id) {
  ref.watch(timerVersionsProvider);
  return ref.watch(businessApiProvider).board(id);
});
