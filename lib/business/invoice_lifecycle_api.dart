import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';
import 'payment_api.dart';
import 'report_api.dart';

class InvoiceEditLine {
  const InvoiceEditLine(
    this.index,
    this.title,
    this.detail,
    this.amount, {
    this.quantity,
    this.unitPrice,
  });
  final int index;
  final String title, detail;
  final String? amount, quantity, unitPrice;
  factory InvoiceEditLine.decode(Map<Object?, Object?> v) => InvoiceEditLine(
    v['sourceIndex'] as int,
    v['title'] as String,
    v['detail'] as String,
    v['amountMinor'] as String?,
    quantity: v['quantityUnits'] as String?,
    unitPrice: v['unitPriceMinor'] as String?,
  );
  Map<String, Object?> encode() => {
    'sourceIndex': index,
    'title': title,
    'detail': detail,
    'amountMinor': amount,
    'quantityUnits': quantity,
    'unitPriceMinor': unitPrice,
  };
}

class InvoiceSourceClaim {
  const InvoiceSourceClaim(this.id, this.label);
  final String id, label;
  factory InvoiceSourceClaim.decode(Map<Object?, Object?> v) =>
      InvoiceSourceClaim(v['id'] as String, v['label'] as String);
}

class InvoiceLifecycleData {
  InvoiceLifecycleData(
    this.invoice,
    List<InvoiceEditLine> lines,
    List<InvoiceSourceClaim> claims,
    this.taxUnits,
  ) : lines = List.unmodifiable(lines),
      claims = List.unmodifiable(claims);
  final InvoicePayments invoice;
  final List<InvoiceEditLine> lines;
  final List<InvoiceSourceClaim> claims;
  final int? taxUnits;
  factory InvoiceLifecycleData.decode(Map<Object?, Object?> v) =>
      InvoiceLifecycleData(
        InvoicePayments.decode(v['invoice'] as Map<Object?, Object?>),
        (v['lines'] as List)
            .map((e) => InvoiceEditLine.decode(e as Map<Object?, Object?>))
            .toList(),
        (v['claims'] as List)
            .map((e) => InvoiceSourceClaim.decode(e as Map<Object?, Object?>))
            .toList(),
        v['taxUnits'] as int?,
      );
}

class InvoiceVoidDraft {
  const InvoiceVoidDraft(this.invoiceId, this.reason, this.timezone);
  final String invoiceId, reason, timezone;
  Map<String, Object?> encode() => {
    'invoiceId': invoiceId,
    'reason': reason,
    'timezone': timezone,
    'confirmed': true,
  };
}

class InvoiceReplacementDraft {
  InvoiceReplacementDraft({
    required this.invoiceId,
    required this.replacementId,
    required List<InvoiceEditLine> lines,
    required this.taxUnits,
    required this.reason,
    required this.timezone,
    required List<String> releasedClaims,
    this.addedWork,
  }) : lines = List.unmodifiable(lines),
       releasedClaims = List.unmodifiable(releasedClaims);
  final String invoiceId, replacementId, reason, timezone;
  final int taxUnits;
  final List<InvoiceEditLine> lines;
  final List<String> releasedClaims;
  final ReportSelection? addedWork;
  Map<String, Object?> encode() => {
    'invoiceId': invoiceId,
    'replacementId': replacementId,
    'lines': lines.map((e) => e.encode()).toList(),
    'taxUnits': taxUnits,
    'reason': reason,
    'timezone': timezone,
    'confirmed': true,
    'releaseClaimIds': releasedClaims,
    'addedWork': addedWork?.encode(),
  };
}

class InvoiceLifecycleApi {
  const InvoiceLifecycleApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<InvoiceLifecycleData> read(String invoiceId) async =>
      InvoiceLifecycleData.decode(
        (await _channel.invokeMapMethod<Object?, Object?>(
          'invoiceLifecycleContext',
          {'invoiceId': invoiceId},
        ))!,
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

  Future<TimerOutcome> voidInvoice(
    String id,
    int version,
    InvoiceVoidDraft draft,
  ) => _save('voidInvoice', id, version, draft.encode());
  Future<TimerOutcome> replace(
    String id,
    int version,
    InvoiceReplacementDraft draft,
  ) => _save('replaceInvoice', id, version, draft.encode());
}

final invoiceLifecycleApiProvider = Provider<InvoiceLifecycleApi>(
  (ref) => const InvoiceLifecycleApi(),
);
final invoiceLifecycleProvider =
    FutureProvider.family<InvoiceLifecycleData, String>((ref, id) {
      ref.watch(timerVersionsProvider);
      return ref.watch(invoiceLifecycleApiProvider).read(id);
    });

/// Exact preview. The native writer independently recomputes these amounts.
({String subtotal, String tax, String total, String credit, String due})
replacementTotals(
  Iterable<InvoiceEditLine> lines,
  String addedSubtotal,
  int taxUnits,
  String paid,
) {
  final canonical = RegExp(r'^(0|[1-9][0-9]*)$');
  if (taxUnits < 0 ||
      taxUnits > 1000000 ||
      !canonical.hasMatch(addedSubtotal) ||
      !canonical.hasMatch(paid) ||
      lines.any(
        (line) => line.amount != null && !canonical.hasMatch(line.amount!),
      )) {
    throw const FormatException('Review exact line amounts, credit and tax.');
  }
  final subtotal =
      lines.fold(
        BigInt.zero,
        (sum, line) => sum + BigInt.parse(line.amount ?? '0'),
      ) +
      BigInt.parse(addedSubtotal);
  final tax =
      (subtotal * BigInt.from(taxUnits) + BigInt.from(500000)) ~/
      BigInt.from(1000000);
  final total = subtotal + tax, credit = BigInt.parse(paid);
  if (total.toString().length > 4096) {
    throw const FormatException('The replacement amount is too long.');
  }
  if (total < credit) {
    throw const FormatException(
      'The replacement total cannot be below the existing payment credit.',
    );
  }
  return (
    subtotal: subtotal.toString(),
    tax: tax.toString(),
    total: total.toString(),
    credit: paid,
    due: (total - credit).toString(),
  );
}
