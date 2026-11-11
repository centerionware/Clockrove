import 'dart:math';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

String newBusinessId() => List.generate(
  16,
  (_) => Random.secure().nextInt(256),
).map((v) => v.toRadixString(16).padLeft(2, '0')).join();

/// Decimal input and display never pass through floating point.
String? parseMoney(String input, int digits) {
  final value = input.trim();
  if (value.isEmpty) return null;
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
  final minor =
      BigInt.parse(parts[0]) * BigInt.from(10).pow(digits) +
      BigInt.parse(
        fraction.padRight(digits, '0').isEmpty
            ? '0'
            : fraction.padRight(digits, '0'),
      );
  if (minor > BigInt.parse('9223372036854775807')) {
    throw const FormatException('Amount is too large.');
  }
  return minor.toString();
}

String money(String? minor, int digits) {
  if (minor == null) return '';
  final value = BigInt.parse(minor).toString().padLeft(digits + 1, '0');
  return digits == 0
      ? value
      : '${value.substring(0, value.length - digits)}.${value.substring(value.length - digits)}';
}

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
    this.dueDate,
    this.priceMinor,
    this.completedMillis,
  });
  final String id, columnId, title, description, currency;
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
  );
}

class TaskTemplate {
  const TaskTemplate(
    this.id,
    this.title,
    this.description,
    this.priceMinor,
    this.currency,
    this.source,
  );
  final String id, title, description, currency;
  final String? priceMinor, source;
  factory TaskTemplate.decode(Map<Object?, Object?> v) => TaskTemplate(
    v['id'] as String,
    v['title'] as String,
    v['description'] as String,
    v['priceMinor'] as String?,
    v['currency'] as String,
    v['sourceJobName'] as String?,
  );
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
        'currency': c.currency,
      });
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
final boardProvider = FutureProvider.family<JobBoard, String>((ref, id) {
  ref.watch(timerVersionsProvider);
  return ref.watch(businessApiProvider).board(id);
});
