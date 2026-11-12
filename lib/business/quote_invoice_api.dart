import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import 'quote_api.dart';

class QuoteInvoiceRequest {
  const QuoteInvoiceRequest({required this.quoteId, required this.revision, required this.jobId,
    required this.firstDate, required this.lastDate, required this.timezone});
  final String quoteId, jobId, firstDate, lastDate, timezone;
  final int revision;
  Map<String, Object?> encode() => {'quoteId': quoteId, 'revision': revision, 'jobId': jobId,
    'firstDate': firstDate, 'lastDate': lastDate, 'timezone': timezone};
}

class QuoteInvoiceTime {
  const QuoteInvoiceTime(this.sessionId, this.start, this.end, {this.label = '', this.durationMillis = ''});
  factory QuoteInvoiceTime.decode(Map<Object?, Object?> value) => QuoteInvoiceTime(value['sessionId'] as String,
    value['start'] as int, value['end'] as int, label: value['label'] as String, durationMillis: value['durationMillis'] as String);
  final String sessionId, label, durationMillis;
  final int start, end;
  String get key => '$sessionId/$start/$end';
  Map<String, Object?> encode() => {'sessionId': sessionId, 'start': start, 'end': end};
}

class QuoteInvoiceTask {
  QuoteInvoiceTask(Map<Object?, Object?> value)
    : id = value['taskId'] as String, title = value['title'] as String, lineId = value['lineId'] as String,
      actualQuantity = value['actualQuantityUnits'] as String, estimatedQuantity = value['estimatedQuantityUnits'] as String,
      currentPrice = value['currentUnitPriceMinor'] as String?, acceptedPrice = value['acceptedUnitPriceMinor'] as String,
      completedLabel = value['completedLabel'] as String;
  final String id, title, lineId, actualQuantity, estimatedQuantity, acceptedPrice, completedLabel;
  final String? currentPrice;
}

class QuoteInvoiceExclusion {
  QuoteInvoiceExclusion(Map<Object?, Object?> value)
    : invoiceId = value['invoiceId'] as String, number = value['number'] as String, title = value['title'] as String? ?? '';
  final String invoiceId, number, title;
}

class QuoteInvoiceContext {
  QuoteInvoiceContext(Map<Object?, Object?> value)
    : version = value['version'] as int, problem = value['problem'] as String?,
      issued = value['issued'] == null ? null : QuoteRevision(value['issued'] as Map<Object?, Object?>),
      historical = value['historical'] as bool? ?? false,
      mode = value['mode'] as String? ?? '', currency = value['currency'] as String? ?? '', digits = value['digits'] as int? ?? 0,
      jobName = value['jobName'] as String? ?? '', archived = value['archived'] as bool? ?? false,
      currentContact = value['currentContact'] as String? ?? '', estimatedHours = value['estimatedHoursUnits'] as String? ?? '0',
      currentCustomer = value['currentCustomer'] as String? ?? '', currentEmail = value['currentEmail'] as String? ?? '',
      currentTax = value['currentTaxUnits'] as int? ?? 0, currentRate = value['currentHourlyMinor'] as String?, acceptedRate = value['acceptedHourlyMinor'] as String?,
      tasks = List.unmodifiable((value['tasks'] as List? ?? const []).map((v) => QuoteInvoiceTask(v as Map<Object?, Object?>))),
      times = List.unmodifiable((value['times'] as List? ?? const []).map((v) => QuoteInvoiceTime.decode(v as Map<Object?, Object?>))),
      excluded = List.unmodifiable((value['excluded'] as List? ?? const []).map((v) => QuoteInvoiceExclusion(v as Map<Object?, Object?>))),
      legacy = List.unmodifiable((value['legacy'] as List? ?? const []).map((v) => QuoteInvoiceExclusion(v as Map<Object?, Object?>))),
      unfinalized = List.unmodifiable((value['unfinalized'] as List? ?? const []).map((v) {
        final row = v as Map<Object?, Object?>;
        return (sessionId: row['sessionId'] as String, reason: row['reason'] as String);
      }));
  final int version, digits, currentTax;
  final String? problem, currentRate, acceptedRate;
  final QuoteRevision? issued;
  final bool historical, archived;
  final String mode, currency, jobName, currentCustomer, currentEmail, currentContact, estimatedHours;
  final List<QuoteInvoiceTask> tasks;
  final List<QuoteInvoiceTime> times;
  final List<QuoteInvoiceExclusion> excluded, legacy;
  final List<({String sessionId, String reason})> unfinalized;
}

class QuoteInvoiceChoices {
  QuoteInvoiceChoices({required List<String> taskIds, required List<QuoteInvoiceTime> times, required this.note,
    this.currentPrices = false, this.currentTax = false, this.currentParty = false, this.historicalReviewed = false})
    : taskIds = List.unmodifiable(taskIds), times = List.unmodifiable(times);
  final List<String> taskIds;
  final List<QuoteInvoiceTime> times;
  final String note;
  final bool currentPrices, currentTax, currentParty, historicalReviewed;
  Map<String, Object?> encode() => {'taskIds': taskIds, 'times': times.map((time) => time.encode()).toList(),
    'currentPrices': currentPrices, 'currentTax': currentTax, 'currentParty': currentParty,
    'historicalReviewed': historicalReviewed, 'note': note, 'confirmed': true};
}

class QuoteInvoiceCalculation {
  QuoteInvoiceCalculation(Map<Object?, Object?> value)
    : version = value['version'] as int, problem = value['problem'] as String?,
      subtotal = value['subtotalMinor'] as String?, tax = value['taxMinor'] as String?, total = value['totalMinor'] as String?,
      taxUnits = value['taxUnits'] as int?, currency = value['currency'] as String?, digits = value['digits'] as int?,
      customer = value['customer'] as String? ?? '', email = value['email'] as String? ?? '';
  final int version;
  final int? taxUnits, digits;
  final String? problem, subtotal, tax, total, currency;
  final String customer, email;
}

class QuoteRelatedInvoice {
  QuoteRelatedInvoice(Map<Object?, Object?> value)
    : id = value['invoiceId'] as String, number = value['number'] as String, createdLabel = value['createdLabel'] as String? ?? 'Creation date unavailable', revision = value['revision'] as int,
      originalId = value['originalInvoiceId'] as String, created = value['created'] as int;
  final String id, number, originalId, createdLabel;
  final int revision, created;
}

class QuoteInvoiceCursor {
  const QuoteInvoiceCursor(this.created, this.invoiceId);
  final int created;
  final String invoiceId;
  Map<String, Object?> encode() => {'created': created, 'invoiceId': invoiceId};
}

class QuoteRelatedInvoices {
  QuoteRelatedInvoices(Map<Object?, Object?> value)
    : invoices = List.unmodifiable((value['invoices'] as List).map((v) => QuoteRelatedInvoice(v as Map<Object?, Object?>))),
      next = value['next'] == null ? null : _cursor(value['next'] as Map<Object?, Object?>);
  static QuoteInvoiceCursor _cursor(Map<Object?, Object?> value) => QuoteInvoiceCursor(value['created'] as int, value['invoiceId'] as String);
  final List<QuoteRelatedInvoice> invoices;
  final QuoteInvoiceCursor? next;
}

class QuoteInvoiceApi {
  const QuoteInvoiceApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<QuoteInvoiceContext> read(QuoteInvoiceRequest request) async => QuoteInvoiceContext(
    (await _channel.invokeMapMethod<Object?, Object?>('quoteInvoiceContext', request.encode()))!);
  Future<QuoteInvoiceCalculation> calculate(QuoteInvoiceRequest request, QuoteInvoiceChoices choices) async => QuoteInvoiceCalculation(
    (await _channel.invokeMapMethod<Object?, Object?>('calculateQuoteInvoice', {'request': request.encode(), 'choices': choices.encode()}))!);
  Future<TimerOutcome> prepare(String id, int version, String invoiceId, QuoteInvoiceRequest request, QuoteInvoiceChoices choices) async {
    final value = (await _channel.invokeMapMethod<Object?, Object?>('prepareQuoteInvoice', {'id': id, 'version': version,
      'value': {'invoiceId': invoiceId, 'request': request.encode(), 'choices': choices.encode()}}))!;
    return TimerOutcome(value['version'] as int, value['conflict'] as String?);
  }
  Future<QuoteRelatedInvoices> invoices(String quoteId, {QuoteInvoiceCursor? before, int limit = 20}) async => QuoteRelatedInvoices(
    (await _channel.invokeMapMethod<Object?, Object?>('quoteInvoices', {'quoteId': quoteId, 'before': before?.encode(), 'limit': limit}))!);
}

final quoteInvoiceApiProvider = Provider<QuoteInvoiceApi>((ref) => const QuoteInvoiceApi());
