import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

class InvoicePayment {
  const InvoicePayment({
    required this.id,
    required this.originalInvoiceId,
    required this.amount,
    required this.currency,
    required this.received,
    required this.timezone,
    required this.offsetSeconds,
    required this.receivedLabel,
    required this.method,
    required this.reference,
    required this.note,
    required this.recorded,
    this.currentInvoiceId,
    this.reversalId,
    this.reversalNote,
  });
  final String id,
      originalInvoiceId,
      amount,
      currency,
      timezone,
      receivedLabel,
      method,
      reference,
      note;
  final int received, offsetSeconds, recorded;
  final String? reversalId, reversalNote, currentInvoiceId;
  String get allocatedInvoiceId => currentInvoiceId ?? originalInvoiceId;
  factory InvoicePayment.decode(Map<Object?, Object?> v) => InvoicePayment(
    id: v['id'] as String,
    originalInvoiceId: v['originalInvoiceId'] as String,
    currentInvoiceId: v['currentInvoiceId'] as String?,
    amount: v['amountMinor'] as String,
    currency: v['currency'] as String,
    received: v['received'] as int,
    timezone: v['timezone'] as String,
    offsetSeconds: v['offsetSeconds'] as int,
    receivedLabel: v['receivedLabel'] as String,
    method: v['method'] as String,
    reference: v['reference'] as String,
    note: v['note'] as String,
    recorded: v['recorded'] as int,
    reversalId: v['reversalId'] as String?,
    reversalNote: v['reversalNote'] as String?,
  );
}

class InvoiceCopy {
  const InvoiceCopy(this.id, this.kind, this.createdLabel, this.paid, this.due);
  final String id, kind, createdLabel, paid, due;
  factory InvoiceCopy.decode(Map<Object?, Object?> v) => InvoiceCopy(
    v['id'] as String,
    v['kind'] as String,
    v['createdLabel'] as String,
    v['paidMinor'] as String,
    v['dueMinor'] as String,
  );
}

class InvoiceCancellation {
  const InvoiceCancellation(
    this.replacementId,
    this.credit,
    this.cancelled,
    this.createdLabel,
    this.reason,
  );
  final String? replacementId;
  final String credit, cancelled, createdLabel, reason;
  factory InvoiceCancellation.decode(Map<Object?, Object?> v) =>
      InvoiceCancellation(
        v['replacementId'] as String?,
        v['creditedMinor'] as String,
        v['cancelledMinor'] as String,
        v['createdLabel'] as String,
        v['reason'] as String,
      );
}

class InvoicePayments {
  InvoicePayments({
    required this.version,
    required this.invoiceId,
    required this.number,
    required this.jobId,
    required this.jobName,
    required this.customer,
    required this.currency,
    required this.digits,
    required this.total,
    required this.paid,
    required this.due,
    required this.status,
    required this.legacy,
    required this.jobDeleted,
    required this.accountingEmail,
    required this.customerEmail,
    required this.now,
    required this.timezone,
    required this.localNow,
    required this.nowOffsetSeconds,
    required this.nowLabel,
    required List<InvoicePayment> payments,
    required List<InvoiceCopy> copies,
    this.collectedTax,
    this.cancellation,
    this.replacesInvoiceId,
  }) : payments = List.unmodifiable(payments),
       copies = List.unmodifiable(copies);
  final int version, digits, now, nowOffsetSeconds;
  final String invoiceId,
      number,
      jobId,
      jobName,
      customer,
      currency,
      total,
      paid,
      due,
      status,
      accountingEmail,
      customerEmail,
      timezone,
      localNow,
      nowLabel;
  final String? collectedTax, replacesInvoiceId;
  final InvoiceCancellation? cancellation;
  final bool legacy, jobDeleted;
  final List<InvoicePayment> payments;
  final List<InvoiceCopy> copies;
  factory InvoicePayments.decode(Map<Object?, Object?> v) => InvoicePayments(
    version: v['version'] as int,
    invoiceId: v['invoiceId'] as String,
    number: v['number'] as String,
    jobId: v['jobId'] as String,
    jobName: v['jobName'] as String,
    customer: v['customer'] as String,
    currency: v['currency'] as String,
    digits: v['digits'] as int,
    total: v['totalMinor'] as String,
    paid: v['paidMinor'] as String,
    due: v['dueMinor'] as String,
    status: v['status'] as String,
    collectedTax: v['collectedTaxMinor'] as String?,
    replacesInvoiceId: v['replacesInvoiceId'] as String?,
    cancellation: v['void'] == null
        ? null
        : InvoiceCancellation.decode(v['void'] as Map<Object?, Object?>),
    legacy: v['legacy'] as bool,
    jobDeleted: v['jobDeleted'] as bool,
    accountingEmail: v['accountingEmail'] as String,
    customerEmail: v['customerEmail'] as String,
    now: v['now'] as int,
    timezone: v['timezone'] as String,
    localNow: v['localNow'] as String,
    nowOffsetSeconds: v['nowOffsetSeconds'] as int,
    nowLabel: v['nowLabel'] as String,
    payments: (v['payments'] as List)
        .map((p) => InvoicePayment.decode(p as Map<Object?, Object?>))
        .toList(),
    copies: (v['copies'] as List)
        .map((p) => InvoiceCopy.decode(p as Map<Object?, Object?>))
        .toList(),
  );
}

class PaymentDraft {
  const PaymentDraft(
    this.paymentId,
    this.invoiceId,
    this.amount,
    this.currency,
    this.received,
    this.timezone,
    this.method,
    this.reference,
    this.note,
  );
  final String paymentId,
      invoiceId,
      amount,
      currency,
      timezone,
      method,
      reference,
      note;
  final int received;
  Map<String, Object?> encode() => {
    'paymentId': paymentId,
    'invoiceId': invoiceId,
    'amountMinor': amount,
    'currency': currency,
    'received': received,
    'timezone': timezone,
    'method': method,
    'reference': reference,
    'note': note,
  };
}

class PaymentReversal {
  const PaymentReversal(
    this.reversalId,
    this.invoiceId,
    this.paymentId,
    this.effective,
    this.timezone,
    this.note,
  );
  final String reversalId, invoiceId, paymentId, timezone, note;
  final int effective;
  Map<String, Object?> encode() => {
    'reversalId': reversalId,
    'invoiceId': invoiceId,
    'paymentId': paymentId,
    'effective': effective,
    'timezone': timezone,
    'note': note,
  };
}

class PaymentApi {
  const PaymentApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<InvoicePayments> read(String invoiceId) async =>
      InvoicePayments.decode(
        (await _channel.invokeMapMethod<Object?, Object?>('invoicePayments', {
          'invoiceId': invoiceId,
        }))!,
      );
  Future<TimerOutcome> _save(
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

  Future<TimerOutcome> record(String id, int version, PaymentDraft draft) =>
      _save('recordInvoicePayment', id, version, draft.encode());
  Future<TimerOutcome> reverse(String id, int version, PaymentReversal draft) =>
      _save('reverseInvoicePayment', id, version, draft.encode());
  Future<TimerOutcome> balanceCopy(
    String id,
    int version,
    String copyToken,
    String invoiceId,
  ) => _save('prepareInvoiceBalanceCopy', id, version, {
    'copyToken': copyToken,
    'invoiceId': invoiceId,
  });
}

final paymentApiProvider = Provider<PaymentApi>((ref) => const PaymentApi());
final invoicePaymentsProvider = FutureProvider.family<InvoicePayments, String>((
  ref,
  id,
) {
  ref.watch(timerVersionsProvider);
  return ref.watch(paymentApiProvider).read(id);
});

String invoicePaymentStatus(String status) => switch (status) {
  'UNPAID' => 'Unpaid',
  'PARTIALLY_PAID' => 'Partially paid',
  'PAID' => 'Paid',
  'NOTHING_DUE' => 'Nothing due',
  'VOID' => 'Voided',
  _ => 'Needs review',
};
