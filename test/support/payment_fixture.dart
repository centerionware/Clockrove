import 'package:clockrove/business/payment_api.dart';

InvoicePayments paymentFixture({
  int version = 1,
  String invoiceId = 'invoice',
  String paid = '0',
  String due = '10750',
  String status = 'UNPAID',
  bool legacy = false,
  bool deleted = false,
  List<InvoicePayment> payments = const [],
  List<InvoiceCopy> copies = const [],
}) => InvoicePayments(
  version: version, invoiceId: invoiceId, number: 'INV-1000', jobId: 'job', jobName: 'Synthetic invoice job',
  customer: 'Synthetic customer', currency: 'USD', digits: 2, total: '10750', paid: paid, due: due, status: status,
  legacy: legacy, jobDeleted: deleted, accountingEmail: 'accounts@example.test', customerEmail: 'customer@example.test',
  now: 1793494800000, timezone: 'UTC', localNow: '2026-11-01T01:00:00.000', nowOffsetSeconds: 0, nowLabel: '2026-11-01T01:00:00.000 +00:00',
  payments: payments, copies: copies,
);
