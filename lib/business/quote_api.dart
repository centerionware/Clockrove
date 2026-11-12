import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';
import 'business_api.dart';
import 'report_api.dart';

String quoteStatus(String value) => switch (value) {
  'DRAFT' => 'Draft', 'ISSUED' => 'Issued', 'ACCEPTED' => 'Accepted locally',
  'DECLINED' => 'Declined locally', 'EXPIRED' => 'Expired', _ => 'Quote',
};

class QuoteLine {
  const QuoteLine(
    this.id,
    this.title,
    this.detail,
    this.kind,
    this.quantity,
    this.unitPrice,
  );
  final String id, title, detail, kind, quantity, unitPrice;
  factory QuoteLine.decode(Map<String, Object?> value) => QuoteLine(
    value['id'] as String,
    value['title'] as String,
    value['detail'] as String,
    value['kind'] as String,
    value['quantity'] as String,
    value['unitPrice'] as String,
  );
  Map<String, Object?> encode() => {
    'lineId': id,
    'title': title,
    'detail': detail,
    'kind': kind,
    'quantityUnits': quantity,
    'unitPriceMinor': unitPrice,
  };
}

class QuoteDraft {
  QuoteDraft({
    required this.id,
    required this.title,
    required this.currency,
    required this.taxUnits,
    required List<QuoteLine> lines,
    this.customerId,
    this.jobId,
    this.validUntil,
    this.terms = '',
    this.notes = '',
  }) : lines = List.unmodifiable(lines);
  final String id, title, currency, terms, notes;
  final String? customerId, jobId, validUntil;
  final int taxUnits;
  final List<QuoteLine> lines;
  factory QuoteDraft.decode(Map<String, Object?> value) => QuoteDraft(
    id: value['id'] as String,
    title: value['title'] as String,
    currency: value['currency'] as String,
    taxUnits: value['taxUnits'] as int,
    customerId: value['customerId'] as String?,
    jobId: value['jobId'] as String?,
    validUntil: value['validUntil'] as String?,
    terms: value['terms'] as String,
    notes: value['notes'] as String,
    lines: (value['lines'] as List)
        .map((v) => QuoteLine.decode(v as Map<String, Object?>))
        .toList(),
  );
  Map<String, Object?> encode() => {
    'quoteId': id,
    'title': title,
    'currency': currency,
    'taxUnits': taxUnits,
    'customerId': customerId,
    'jobId': jobId,
    'validUntil': validUntil,
    'terms': terms,
    'notes': notes,
    'lines': lines.map((v) => v.encode()).toList(),
  };
}

class QuoteRevision {
  QuoteRevision(Map<Object?, Object?> value)
    : id = value['quoteId'] as String,
      revision = value['revision'] as int,
      number = value['number'] as String,
      created = value['created'] as int,
      decision = value['decision'] as String?,
      decisionNote = value['decisionNote'] as String?,
      _frozen = Map.unmodifiable(
        jsonDecode(value['payload'] as String) as Map<String, Object?>,
      );
  final String id, number;
  final int revision, created;
  final String? decision, decisionNote;
  final Map<String, Object?> _frozen;
  QuoteDraft get draft =>
      QuoteDraft.decode(_frozen['draft'] as Map<String, Object?>);
  String get total => _frozen['total'] as String;
  String get subtotal => _frozen['subtotal'] as String;
  String get tax => _frozen['tax'] as String;
  String get customer => _frozen['customer'] as String;
  int get digits => _frozen['digits'] as int;
}

class QuoteData {
  const QuoteData(this.version, this.draft, this.latest, {this.draftDigits, this.today = '', this.timezone = '', this.draftCustomer = '', this.draftEmail = '', this.draftCompany = '', this.draftStyle = ''});
  final int version;
  final QuoteDraft? draft;
  final QuoteRevision? latest;
  final int? draftDigits;
  final String today, timezone, draftCustomer, draftEmail, draftCompany, draftStyle;
  factory QuoteData.decode(Map<Object?, Object?> value) => QuoteData(
    value['version'] as int,
    value['draftJson'] == null
        ? null
        : QuoteDraft.decode(
            jsonDecode(value['draftJson'] as String) as Map<String, Object?>,
          ),
    value['latest'] == null
        ? null
        : QuoteRevision(value['latest'] as Map<Object?, Object?>),
    draftDigits: value['draftDigits'] as int?, today: value['today'] as String? ?? '', timezone: value['timezone'] as String? ?? '',
    draftCustomer: value['draftCustomer'] as String? ?? '', draftEmail: value['draftEmail'] as String? ?? '', draftCompany: value['draftCompany'] as String? ?? '', draftStyle: value['draftStyle'] as String? ?? '',
  );
}

class QuoteCursor {
  const QuoteCursor(this.created, this.id);
  final int created;
  final String id;
  Map<String, Object?> encode() => {'created': created, 'id': id};
}

class QuoteSummary {
  QuoteSummary(Map<Object?, Object?> value)
    : id = value['quoteId'] as String,
      number = value['number'] as String?,
      title = value['title'] as String,
      currency = value['currency'] as String,
      total = value['total'] as String,
      customer = value['customer'] as String,
      status = value['status'] as String,
      hasDraft = value['hasDraft'] as bool,
      revision = value['revision'] as int?,
      validUntil = value['validUntil'] as String?;
  final String id, title, currency, total, customer, status;
  final String? number, validUntil;
  final bool hasDraft;
  final int? revision;
}

class QuotePage {
  QuotePage(Map<Object?, Object?> value)
    : version = value['version'] as int,
      today = value['today'] as String,
      timezone = value['timezone'] as String,
      entries = List.unmodifiable(
        (value['entries'] as List).map(
          (v) => QuoteSummary(v as Map<Object?, Object?>),
        ),
      ),
      next = value['next'] == null
          ? null
          : QuoteCursor(
              (value['next'] as Map)['created'] as int,
              (value['next'] as Map)['id'] as String,
            );
  final int version;
  final String today, timezone;
  final List<QuoteSummary> entries;
  final QuoteCursor? next;
}

class QuoteHistoryEntry {
  QuoteHistoryEntry(Map<Object?, Object?> value)
    : revision = value['revision'] as int,
      created = value['created'] as int,
      decision = value['decision'] as String?;
  final int revision, created;
  final String? decision;
}

/// Exact live preview. Native recomputation remains authoritative at issue.
({String subtotal, String tax, String total}) quoteTotals(
  List<QuoteLine> lines,
  int taxUnits,
) {
  if (lines.isEmpty ||
      lines.length > 2000 ||
      taxUnits < 0 ||
      taxUnits > 1000000) {
    throw const FormatException(
      'Add a line and enter a tax percentage from 0 to 100.',
    );
  }
  final moneyPattern = RegExp(r'^(0|[1-9][0-9]{0,1023})$');
  final quantityPattern = RegExp(r'^[1-9][0-9]{0,18}$');
  var subtotal = BigInt.zero;
  for (final line in lines) {
    if (!moneyPattern.hasMatch(line.unitPrice) ||
        !quantityPattern.hasMatch(line.quantity) ||
        BigInt.parse(line.quantity) > BigInt.parse('9223372036854775807')) {
      throw const FormatException(
        'Review the quantity and unit price for each line.',
      );
    }
    subtotal += BigInt.parse(taskAmount(line.unitPrice, line.quantity)!);
  }
  final tax =
      (subtotal * BigInt.from(taxUnits) + BigInt.from(500000)) ~/
      BigInt.from(1000000);
  final total = subtotal + tax;
  if (total.toString().length > 4096) {
    throw const FormatException('The quote is too large.');
  }
  return (
    subtotal: subtotal.toString(),
    tax: tax.toString(),
    total: total.toString(),
  );
}

class QuoteApi {
  const QuoteApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  static const _documents = MethodChannel('clockrove/documents/v1');
  Future<int> precision(String currency, {String? quoteId}) async =>
      (await _channel.invokeMapMethod<Object?, Object?>('currencyPrecision', {'currency': currency, 'quoteId': quoteId}))!['digits'] as int;
  Future<QuoteData> read(String id) async => QuoteData.decode(
    (await _channel.invokeMapMethod<Object?, Object?>('quoteContext', {
      'quoteId': id,
    }))!,
  );
  Future<QuotePage> page({QuoteCursor? before, String? status}) async => QuotePage(
    (await _channel.invokeMapMethod<Object?, Object?>('quotes', {
      'before': before?.encode(),
            'status': status,
      'limit': 20,
    }))!,
  );
  Future<List<QuoteHistoryEntry>> history(String id, {int? before}) async =>
      (await _channel.invokeListMethod<Object?>('quoteHistory', {
        'quoteId': id,
        'before': before,
        'limit': 20,
      }))!.map((v) => QuoteHistoryEntry(v as Map<Object?, Object?>)).toList();
  Future<QuoteRevision?> revision(String id, int revision) async {
    final value = (await _channel.invokeMapMethod<Object?, Object?>(
      'quoteRevision',
      {'quoteId': id, 'revision': revision},
    ))!;
    return value['unavailable'] == true ? null : QuoteRevision(value);
  }

  Future<TimerOutcome> _change(
    String method,
    String id,
    int version,
    Map<String, Object?> value,
  ) async {
    final result = (await _channel.invokeMapMethod<Object?, Object?>(method, {
      'id': id,
      'version': version,
      'value': value,
    }))!;
    return TimerOutcome(
      result['version'] as int,
      result['conflict'] as String?,
    );
  }

  Future<TimerOutcome> save(String id, int version, QuoteDraft value) =>
      _change('saveQuote', id, version, value.encode());
  Future<TimerOutcome> issue(String id, int version, String quoteId) => _change(
    'issueQuote',
    id,
    version,
    {'quoteId': quoteId, 'confirmed': true},
  );
  Future<TimerOutcome> decide(
    String id,
    int version,
    String quoteId,
    int revision,
    String decision,
    String note,
  ) => _change('decideQuote', id, version, {
    'quoteId': quoteId,
    'revision': revision,
    'decision': decision,
    'note': note,
    'confirmed': true,
  });
  Future<InvoicePage> preview(String id, int revision, int page) async {
    final value = (await _documents.invokeMapMethod<Object?, Object?>(
      'quotePreview',
      {'quoteId': id, 'revision': revision, 'page': page},
    ))!;
    return InvoicePage(
      value['page'] as int,
      value['pages'] as int,
      value['png'] as Uint8List,
      notice: value['notice'] as String?,
    );
  }

  Future<void> email(String id, int revision) => _documents.invokeMethod<void>(
    'emailQuote',
    {'quoteId': id, 'revision': revision},
  );
  Future<void> share(String id, int revision) => _documents.invokeMethod<void>(
    'shareQuote',
    {'quoteId': id, 'revision': revision},
  );
  Future<bool> savePdf(String id, int revision) async => (await _documents
      .invokeMethod<bool>('saveQuote', {'quoteId': id, 'revision': revision}))!;
}

final quoteApiProvider = Provider<QuoteApi>((ref) => const QuoteApi());
final quoteProvider = FutureProvider.family<QuoteData, String>((ref, id) {
  ref.watch(timerVersionsProvider);
  return ref.watch(quoteApiProvider).read(id);
});
