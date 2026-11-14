import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

class ReportSelection {
  ReportSelection(
    this.firstDate,
    this.lastDate,
    this.timezone,
    List<String> jobs,
  ) : jobs = List.unmodifiable(jobs);
  final String firstDate, lastDate, timezone;
  final List<String> jobs;
  Map<String, Object?> encode() => {
    'firstDate': firstDate,
    'lastDate': lastDate,
    'timezone': timezone,
    'jobIds': jobs,
  };
  @override
  bool operator ==(Object other) =>
      other is ReportSelection &&
      firstDate == other.firstDate &&
      lastDate == other.lastDate &&
      timezone == other.timezone &&
      jobs.join('\u0000') == other.jobs.join('\u0000');
  @override
  int get hashCode =>
      Object.hash(firstDate, lastDate, timezone, Object.hashAll(jobs));
}

class ReportJob {
  const ReportJob(this.id, this.name, this.archived);
  final String id, name;
  final bool archived;
}

class ReportContext {
  const ReportContext(this.timezone, this.jobs, [this.customers = const []]);
  final String timezone;
  final List<ReportJob> jobs;
  final List<ReportCustomer> customers;
}

class ReportCustomer {
  const ReportCustomer(this.id, this.name, this.archived);
  final String id, name;
  final bool archived;
}

class BusinessReportSelection {
  BusinessReportSelection(
    this.firstDate,
    this.lastDate,
    this.timezone, {
    List<String> jobs = const [],
    List<String> customers = const [],
  }) : jobs = List.unmodifiable(jobs),
       customers = List.unmodifiable(customers);
  final String firstDate, lastDate, timezone;
  final List<String> jobs, customers;
  Map<String, Object?> encode() => {
    'firstDate': firstDate,
    'lastDate': lastDate,
    'timezone': timezone,
    'jobIds': jobs,
    'customerIds': customers,
  };
  @override
  bool operator ==(Object other) =>
      other is BusinessReportSelection &&
      firstDate == other.firstDate &&
      lastDate == other.lastDate &&
      timezone == other.timezone &&
      jobs.join('\u0000') == other.jobs.join('\u0000') &&
      customers.join('\u0000') == other.customers.join('\u0000');
  @override
  int get hashCode => Object.hash(
    firstDate,
    lastDate,
    timezone,
    Object.hashAll(jobs),
    Object.hashAll(customers),
  );
}

class BusinessReportSummary {
  const BusinessReportSummary({
    required this.currency,
    required this.digits,
    required this.invoicedSales,
    required this.cashReceived,
    required this.taxBilled,
    required this.taxCollected,
    required this.outstanding,
    required this.labor,
    required this.supplies,
    required this.unclassified,
    required this.directCosts,
    required this.overhead,
    required this.grossProfit,
    required this.netProfit,
    required this.grossMarginPercent,
    required this.netMarginPercent,
    required this.cashSurplus,
    required this.costCount,
    required this.unattributedCostCount,
    required this.legacyInvoiceCount,
    required this.unknownTaxInvoiceCount,
  });
  final String currency, invoicedSales, cashReceived, taxBilled, taxCollected;
  final String outstanding,
      labor,
      supplies,
      unclassified,
      directCosts,
      overhead;
  final String grossProfit, netProfit, cashSurplus;
  final int digits, costCount, unattributedCostCount;
  final int legacyInvoiceCount, unknownTaxInvoiceCount;
  final String? grossMarginPercent, netMarginPercent;
  factory BusinessReportSummary.decode(Map<Object?, Object?> value) =>
      BusinessReportSummary(
        currency: value['currency'] as String,
        digits: value['digits'] as int,
        invoicedSales: value['invoicedSalesMinor'] as String,
        cashReceived: value['cashReceivedMinor'] as String,
        taxBilled: value['taxBilledMinor'] as String,
        taxCollected: value['taxCollectedMinor'] as String,
        outstanding: value['outstandingMinor'] as String,
        labor: value['laborMinor'] as String,
        supplies: value['suppliesMinor'] as String,
        unclassified: value['unclassifiedMinor'] as String,
        directCosts: value['directCostsMinor'] as String,
        overhead: value['overheadMinor'] as String,
        grossProfit: value['grossProfitMinor'] as String,
        netProfit: value['netProfitMinor'] as String,
        grossMarginPercent: value['grossMarginPercent'] as String?,
        netMarginPercent: value['netMarginPercent'] as String?,
        cashSurplus: value['cashSurplusMinor'] as String,
        costCount: value['costCount'] as int,
        unattributedCostCount: value['unattributedCostCount'] as int,
        legacyInvoiceCount: value['legacyInvoiceCount'] as int,
        unknownTaxInvoiceCount: value['unknownTaxInvoiceCount'] as int,
      );
}

class BusinessInvoiceMovement {
  const BusinessInvoiceMovement({
    required this.id,
    required this.invoiceId,
    required this.number,
    required this.kind,
    required this.occurred,
    required this.occurredLabel,
    required this.jobId,
    required this.job,
    required this.customer,
    required this.currency,
    required this.digits,
    required this.salesMinor,
    required this.taxMinor,
    required this.legacyTotalMinor,
    required this.laborMinor,
    required this.suppliesMinor,
    required this.unclassifiedMinor,
    required this.relatedInvoiceId,
    required this.reason,
  });
  final String id,
      invoiceId,
      number,
      kind,
      occurredLabel,
      job,
      customer,
      currency;
  final String? jobId, salesMinor, taxMinor, legacyTotalMinor, laborMinor;
  final String? suppliesMinor, unclassifiedMinor, relatedInvoiceId;
  final int occurred, digits;
  final String reason;
  factory BusinessInvoiceMovement.decode(Map<Object?, Object?> value) =>
      BusinessInvoiceMovement(
        id: value['id'] as String,
        invoiceId: value['invoiceId'] as String,
        number: value['number'] as String,
        kind: value['kind'] as String,
        occurred: value['occurred'] as int,
        occurredLabel: value['occurredLabel'] as String,
        jobId: value['jobId'] as String?,
        job: value['job'] as String,
        customer: value['customer'] as String,
        currency: value['currency'] as String,
        digits: value['digits'] as int,
        salesMinor: value['salesMinor'] as String?,
        taxMinor: value['taxMinor'] as String?,
        legacyTotalMinor: value['legacyTotalMinor'] as String?,
        laborMinor: value['laborMinor'] as String?,
        suppliesMinor: value['suppliesMinor'] as String?,
        unclassifiedMinor: value['unclassifiedMinor'] as String?,
        relatedInvoiceId: value['relatedInvoiceId'] as String?,
        reason: value['reason'] as String,
      );
}

class BusinessCashMovement {
  const BusinessCashMovement({
    required this.id,
    required this.invoiceId,
    required this.paymentId,
    required this.number,
    required this.kind,
    required this.occurred,
    required this.occurredLabel,
    required this.job,
    required this.customer,
    required this.currency,
    required this.digits,
    required this.cashMinor,
    required this.creditMinor,
    required this.salesMinor,
    required this.taxMinor,
    required this.reference,
  });
  final String id, invoiceId, paymentId, number, kind, occurredLabel;
  final String job, customer, currency, cashMinor, creditMinor;
  final String? salesMinor, taxMinor;
  final String reference;
  final int occurred, digits;
  factory BusinessCashMovement.decode(Map<Object?, Object?> value) =>
      BusinessCashMovement(
        id: value['id'] as String,
        invoiceId: value['invoiceId'] as String,
        paymentId: value['paymentId'] as String,
        number: value['number'] as String,
        kind: value['kind'] as String,
        occurred: value['occurred'] as int,
        occurredLabel: value['occurredLabel'] as String,
        job: value['job'] as String,
        customer: value['customer'] as String,
        currency: value['currency'] as String,
        digits: value['digits'] as int,
        cashMinor: value['cashMinor'] as String,
        creditMinor: value['creditMinor'] as String,
        salesMinor: value['salesMinor'] as String?,
        taxMinor: value['taxMinor'] as String?,
        reference: value['reference'] as String,
      );
}

class BusinessCostMovement {
  const BusinessCostMovement({
    required this.id,
    required this.job,
    required this.customer,
    required this.task,
    required this.occurred,
    required this.occurredLabel,
    required this.timezone,
    required this.offsetSeconds,
    required this.currency,
    required this.digits,
    required this.category,
    required this.amountMinor,
    required this.vendor,
    required this.reference,
    required this.note,
  });
  final String id, job, customer, task, occurredLabel, timezone, currency;
  final int occurred, offsetSeconds, digits;
  final String category, amountMinor, vendor, reference, note;
  factory BusinessCostMovement.decode(Map<Object?, Object?> value) =>
      BusinessCostMovement(
        id: value['id'] as String,
        job: value['job'] as String,
        customer: value['customer'] as String,
        task: value['task'] as String,
        occurred: value['occurred'] as int,
        occurredLabel: value['occurredLabel'] as String,
        timezone: value['timezone'] as String,
        offsetSeconds: value['offsetSeconds'] as int,
        currency: value['currency'] as String,
        digits: value['digits'] as int,
        category: value['category'] as String,
        amountMinor: value['amountMinor'] as String,
        vendor: value['vendor'] as String,
        reference: value['reference'] as String,
        note: value['note'] as String,
      );
}

class BusinessFinancialReport {
  const BusinessFinancialReport({
    required this.version,
    required this.timezone,
    required this.firstDate,
    required this.lastDate,
    required this.asOfExclusiveMillis,
    required this.excludedUnknownCustomerCostCount,
    required this.summaries,
    required this.invoices,
    required this.cash,
    required this.costs,
  });
  final int version, asOfExclusiveMillis, excludedUnknownCustomerCostCount;
  final String timezone, firstDate, lastDate;
  final List<BusinessReportSummary> summaries;
  final List<BusinessInvoiceMovement> invoices;
  final List<BusinessCashMovement> cash;
  final List<BusinessCostMovement> costs;
  factory BusinessFinancialReport.decode(
    Map<Object?, Object?> value,
  ) => BusinessFinancialReport(
    version: value['version'] as int,
    timezone: value['timezone'] as String,
    firstDate: value['firstDate'] as String,
    lastDate: value['lastDate'] as String,
    asOfExclusiveMillis: value['asOfExclusiveMillis'] as int,
    excludedUnknownCustomerCostCount:
        value['excludedUnknownCustomerCostCount'] as int,
    summaries: (value['summaries'] as List)
        .map(
          (row) => BusinessReportSummary.decode(row as Map<Object?, Object?>),
        )
        .toList(),
    invoices: (value['invoices'] as List)
        .map(
          (row) => BusinessInvoiceMovement.decode(row as Map<Object?, Object?>),
        )
        .toList(),
    cash: (value['cash'] as List)
        .map((row) => BusinessCashMovement.decode(row as Map<Object?, Object?>))
        .toList(),
    costs: (value['costs'] as List)
        .map((row) => BusinessCostMovement.decode(row as Map<Object?, Object?>))
        .toList(),
  );
}

class ReportTotal {
  const ReportTotal(
    this.jobId,
    this.name,
    this.mode,
    this.currency,
    this.digits,
    this.duration,
    this.rate,
    this.amount,
    this.missing,
  );
  final String jobId, name, mode, currency, duration;
  final int digits, missing;
  final String? rate, amount;
  factory ReportTotal.decode(Map<Object?, Object?> v) => ReportTotal(
    v['jobId'] as String,
    v['jobName'] as String,
    v['mode'] as String,
    v['currency'] as String,
    v['fractionDigits'] as int,
    v['durationMillis'] as String,
    v['hourlyMinor'] as String?,
    v['amountMinor'] as String?,
    v['missingPrices'] as int,
  );
}

class ReportInterval {
  const ReportInterval(
    this.sessionId,
    this.jobId,
    this.name,
    this.date,
    this.start,
    this.end,
    this.startLabel,
    this.endLabel,
    this.origin,
  );
  final String sessionId, jobId, name, date, startLabel, endLabel, origin;
  final int start, end;
  factory ReportInterval.decode(Map<Object?, Object?> v) => ReportInterval(
    v['sessionId'] as String,
    v['jobId'] as String,
    v['jobName'] as String,
    v['date'] as String,
    v['start'] as int,
    v['end'] as int,
    v['startLabel'] as String,
    v['endLabel'] as String,
    v['origin'] as String,
  );
}

class ReportTask {
  const ReportTask(
    this.id,
    this.jobId,
    this.title,
    this.date,
    this.currency,
    this.price, [
    this.quantityUnits = '10000',
  ]);
  final String id, jobId, title, date, currency, quantityUnits;
  final String? price;
  factory ReportTask.decode(Map<Object?, Object?> v) => ReportTask(
    v['id'] as String,
    v['jobId'] as String,
    v['title'] as String,
    v['completedLabel'] as String,
    v['currency'] as String,
    v['priceMinor'] as String?,
    v['quantityUnits'] as String? ?? '10000',
  );
}

class ReportPoint {
  const ReportPoint(
    this.id,
    this.sessionId,
    this.jobId,
    this.name,
    this.measured,
    this.label,
    this.latitude,
    this.longitude,
    this.accuracy,
  );
  final String id, sessionId, jobId, name, label;
  final int measured;
  final double latitude, longitude, accuracy;
  factory ReportPoint.decode(Map<Object?, Object?> v) => ReportPoint(
    v['id'] as String,
    v['sessionId'] as String,
    v['jobId'] as String,
    v['jobName'] as String,
    v['measured'] as int,
    v['measuredLabel'] as String,
    (v['latitude'] as num).toDouble(),
    (v['longitude'] as num).toDouble(),
    (v['accuracy'] as num).toDouble(),
  );
}

class ExcludedWork {
  const ExcludedWork(this.jobId, this.label, this.reason);
  final String jobId, label, reason;
  factory ExcludedWork.decode(Map<Object?, Object?> v) => ExcludedWork(
    v['jobId'] as String,
    v['startLabel'] as String,
    v['reason'] as String,
  );
}

class WorkReport {
  const WorkReport(
    this.version,
    this.workCsv,
    this.gpsCsv,
    this.totals,
    this.intervals,
    this.tasks,
    this.points,
    this.excluded,
  );
  final int version;
  final String workCsv, gpsCsv;
  final List<ReportTotal> totals;
  final List<ReportInterval> intervals;
  final List<ReportTask> tasks;
  final List<ReportPoint> points;
  final List<ExcludedWork> excluded;
  factory WorkReport.decode(Map<Object?, Object?> v) => WorkReport(
    v['version'] as int,
    v['workCsv'] as String,
    v['gpsCsv'] as String,
    (v['totals'] as List)
        .map((e) => ReportTotal.decode(e as Map<Object?, Object?>))
        .toList(),
    (v['intervals'] as List)
        .map((e) => ReportInterval.decode(e as Map<Object?, Object?>))
        .toList(),
    (v['tasks'] as List)
        .map((e) => ReportTask.decode(e as Map<Object?, Object?>))
        .toList(),
    (v['points'] as List)
        .map((e) => ReportPoint.decode(e as Map<Object?, Object?>))
        .toList(),
    (v['excluded'] as List)
        .map((e) => ExcludedWork.decode(e as Map<Object?, Object?>))
        .toList(),
  );
}

class PreparedInvoice {
  const PreparedInvoice(
    this.id,
    this.number,
    this.total,
    this.currency,
    this.style, {
    this.jobId = '',
    this.jobName = '',
    this.customer = '',
    this.createdLabel = '',
    this.jobDeleted = false,
    this.legacy = false,
    this.paid,
    this.due,
    this.status,
    this.digits = 2,
  });
  final String? paid, due, status;
  final int digits;
  final String id, number, total, currency, style;
  final String jobId, jobName, customer, createdLabel;
  final bool jobDeleted, legacy;
}

class InvoiceBillingLine {
  const InvoiceBillingLine(this.title, this.detail, this.amount);
  final String title, detail, amount;
  factory InvoiceBillingLine.decode(Map<Object?, Object?> value) =>
      InvoiceBillingLine(
        value['title'] as String,
        value['detail'] as String,
        value['amount'] as String,
      );
}

class InvoiceCoverageLink {
  const InvoiceCoverageLink(
    this.invoiceId,
    this.number, {
    this.title = '',
    this.detail = '',
  });
  final String invoiceId, number, title, detail;
  factory InvoiceCoverageLink.decode(Map<Object?, Object?> value) =>
      InvoiceCoverageLink(
        value['invoiceId'] as String,
        value['number'] as String,
        title: value['title'] as String? ?? '',
        detail: value['detail'] as String? ?? '',
      );
}

class InvoiceBillingPreview {
  const InvoiceBillingPreview({
    required this.version,
    required this.jobId,
    required this.jobName,
    required this.currency,
    required this.taxUnits,
    required this.lines,
    this.subtotal,
    this.tax,
    this.total,
    this.problem,
    this.excluded = const [],
    this.legacy = const [],
  });
  final int version, taxUnits;
  final String jobId, jobName, currency;
  final String? subtotal, tax, total, problem;
  final List<InvoiceBillingLine> lines;
  final List<InvoiceCoverageLink> excluded, legacy;
  factory InvoiceBillingPreview.decode(Map<Object?, Object?> value) =>
      InvoiceBillingPreview(
        version: value['version'] as int,
        jobId: value['jobId'] as String,
        jobName: value['jobName'] as String,
        currency: value['currency'] as String,
        taxUnits: value['taxUnits'] as int,
        subtotal: value['subtotal'] as String?,
        tax: value['tax'] as String?,
        total: value['total'] as String?,
        problem: value['problem'] as String?,
        lines: (value['lines'] as List)
            .map((v) => InvoiceBillingLine.decode(v as Map<Object?, Object?>))
            .toList(),
        excluded: (value['excluded'] as List)
            .map((v) => InvoiceCoverageLink.decode(v as Map<Object?, Object?>))
            .toList(),
        legacy: (value['legacy'] as List)
            .map((v) => InvoiceCoverageLink.decode(v as Map<Object?, Object?>))
            .toList(),
      );
}

class InvoicePage {
  const InvoicePage(this.index, this.pages, this.png, {this.notice});
  final String? notice;
  final int index, pages;
  final Uint8List png;
}

class ReportApi {
  const ReportApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  static const _documents = MethodChannel('clockrove/documents/v1');
  Future<ReportContext> context() async {
    final v = (await _channel.invokeMapMethod<Object?, Object?>(
      'reportContext',
    ))!;
    return ReportContext(
      v['timezone'] as String,
      (v['jobs'] as List).map((raw) {
        final j = raw as Map<Object?, Object?>;
        return ReportJob(
          j['id'] as String,
          j['name'] as String,
          j['archived'] as bool,
        );
      }).toList(),
      ((v['customers'] as List?) ?? const []).map((raw) {
        final customer = raw as Map<Object?, Object?>;
        return ReportCustomer(
          customer['id'] as String,
          customer['name'] as String,
          customer['archived'] as bool,
        );
      }).toList(),
    );
  }

  Future<WorkReport> report(ReportSelection s) async => WorkReport.decode(
    (await _channel.invokeMapMethod<Object?, Object?>('report', s.encode()))!,
  );
  Future<BusinessFinancialReport> businessReport(
    BusinessReportSelection selection,
  ) async => BusinessFinancialReport.decode(
    (await _channel.invokeMapMethod<Object?, Object?>(
      'businessReport',
      selection.encode(),
    ))!,
  );
  Future<InvoiceBillingPreview> invoiceBillingPreview(
    ReportSelection selection,
  ) async => InvoiceBillingPreview.decode(
    (await _channel.invokeMapMethod<Object?, Object?>(
      'invoiceBillingPreview',
      selection.encode(),
    ))!,
  );
  Future<TimerOutcome> prepare(
    String id,
    int version,
    String invoiceId,
    ReportSelection selection,
  ) async {
    final v = (await _channel.invokeMapMethod<String, Object?>(
      'prepareInvoice',
      {
        'id': id,
        'version': version,
        'value': {...selection.encode(), 'invoiceId': invoiceId},
      },
    ))!;
    return TimerOutcome(v['version'] as int, v['conflict'] as String?);
  }

  Future<List<PreparedInvoice>> invoices(String jobId) => _invoices(jobId);
  Future<List<PreparedInvoice>> allInvoices() => _invoices(null);
  Future<List<PreparedInvoice>> _invoices(String? jobId) async {
    final v = (await _channel.invokeMapMethod<Object?, Object?>('invoices', {
      'jobId': jobId,
    }))!;
    return (v['invoices'] as List).map((raw) {
      final i = raw as Map<Object?, Object?>;
      return PreparedInvoice(
        i['id'] as String,
        i['number'] as String,
        i['total'] as String,
        i['currency'] as String,
        i['style'] as String,
        jobId: i['jobId'] as String? ?? '',
        jobName: i['jobName'] as String? ?? '',
        customer: i['customer'] as String? ?? '',
        createdLabel: i['createdLabel'] as String? ?? '',
        jobDeleted: i['jobDeleted'] as bool? ?? false,
        legacy: i['legacy'] as bool? ?? false,
        paid: i['paidMinor'] as String?,
        due: i['dueMinor'] as String?,
        status: i['status'] as String?,
        digits: i['digits'] as int? ?? 2,
      );
    }).toList();
  }

  Future<InvoicePage> preview(String id, int page) async {
    final v = (await _documents.invokeMapMethod<Object?, Object?>(
      'invoicePreview',
      {'invoiceId': id, 'page': page},
    ))!;
    return InvoicePage(
      v['page'] as int,
      v['pages'] as int,
      v['png'] as Uint8List,
      notice: v['notice'] as String?,
    );
  }

  Future<InvoicePage> previewCopy(String id, String copyId, int page) async {
    final value = (await _documents.invokeMapMethod<Object?, Object?>(
      'invoicePreview',
      {'invoiceId': id, 'copyId': copyId, 'page': page},
    ))!;
    return InvoicePage(
      value['page'] as int,
      value['pages'] as int,
      value['png'] as Uint8List,
      notice: value['notice'] as String?,
    );
  }

  Future<InvoicePage> previewStyle({
    required String style,
    required String currency,
    required int digits,
    required Uint8List? logo,
    required int page,
  }) async {
    final value = (await _documents.invokeMapMethod<Object?, Object?>(
      'settingsInvoicePreview',
      {
        'style': style,
        'currency': currency,
        'digits': digits,
        'logo': logo,
        'page': page,
      },
    ))!;
    return InvoicePage(
      value['page'] as int,
      value['pages'] as int,
      value['png'] as Uint8List,
    );
  }

  Future<void> emailCopy(String id, String copyId) => _documents
      .invokeMethod<void>('emailInvoice', {'invoiceId': id, 'copyId': copyId});
  Future<void> shareCopy(String id, String copyId) => _documents
      .invokeMethod<void>('shareInvoice', {'invoiceId': id, 'copyId': copyId});
  Future<bool> saveCopy(String id, String copyId) async =>
      await _documents.invokeMethod<bool>('saveInvoice', {
        'invoiceId': id,
        'copyId': copyId,
      }) ??
      false;

  Future<void> email(String id) =>
      _documents.invokeMethod<void>('emailInvoice', {'invoiceId': id});
  Future<void> share(String id) =>
      _documents.invokeMethod<void>('shareInvoice', {'invoiceId': id});
  Future<bool> saveCsv(
    ReportSelection selection,
    int version,
    bool gps,
  ) async =>
      await _documents.invokeMethod<bool>('saveReport', {
        ...selection.encode(),
        'version': version,
        'gps': gps,
      }) ??
      false;
  Future<bool> saveBusinessReport(
    BusinessReportSelection selection,
    int version, {
    required bool pdf,
  }) async =>
      await _documents.invokeMethod<bool>('saveBusinessReport', {
        ...selection.encode(),
        'version': version,
        'pdf': pdf,
      }) ??
      false;
  Future<bool> save(String id) async =>
      await _documents.invokeMethod<bool>('saveInvoice', {'invoiceId': id}) ??
      false;
}

final reportApiProvider = Provider<ReportApi>((ref) => const ReportApi());
final invoiceBillingPreviewProvider =
    FutureProvider.family<InvoiceBillingPreview, ReportSelection>((
      ref,
      selection,
    ) {
      ref.watch(timerVersionsProvider);
      return ref.watch(reportApiProvider).invoiceBillingPreview(selection);
    });
final reportProvider = FutureProvider.family<WorkReport, ReportSelection>((
  ref,
  selection,
) {
  ref.watch(timerVersionsProvider);
  return ref.watch(reportApiProvider).report(selection);
});
final businessReportProvider =
    FutureProvider.family<BusinessFinancialReport, BusinessReportSelection>((
      ref,
      selection,
    ) {
      ref.watch(timerVersionsProvider);
      return ref.watch(reportApiProvider).businessReport(selection);
    });
