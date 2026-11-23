import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'invoice_screen.dart';
import 'invoice_lifecycle_editor.dart';
import 'legacy_invoice_screen.dart';
import 'payment_api.dart';
import 'payment_editor.dart';

class InvoiceDetailScreen extends ConsumerWidget {
  const InvoiceDetailScreen({super.key, required this.invoiceId});
  final String invoiceId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final result = ref.watch(invoicePaymentsProvider(invoiceId));
    return result.when(
      skipLoadingOnReload: true,
      skipError: true,
      loading: () => Scaffold(
        appBar: AppBar(title: Text(AppMessage.invoiceDetailTitle.of(context))),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Scaffold(
        appBar: AppBar(title: Text(AppMessage.invoiceDetailTitle.of(context))),
        body: Center(
          child: TextButton(
            onPressed: () => ref.invalidate(invoicePaymentsProvider(invoiceId)),
            child: Text(AppMessage.retryInvoiceDetails.of(context)),
          ),
        ),
      ),
      data: (data) => _InvoiceDetail(
        key: ValueKey(invoiceId),
        data: data,
        refreshFailed: result.hasError,
      ),
    );
  }
}

class _InvoiceDetail extends ConsumerStatefulWidget {
  const _InvoiceDetail({
    super.key,
    required this.data,
    required this.refreshFailed,
  });
  final InvoicePayments data;
  final bool refreshFailed;
  @override
  ConsumerState<_InvoiceDetail> createState() => _InvoiceDetailState();
}

class _InvoiceDetailState extends ConsumerState<_InvoiceDetail> {
  String? _copyToken;
  bool _pendingCopy = false;
  Future<void> _open([
    String? copyId,
    bool compose = false,
    String? ownerInvoiceId,
  ]) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) => InvoiceScreen(
          invoiceId: ownerInvoiceId ?? widget.data.invoiceId,
          copyId: copyId,
          composeOnOpen: compose,
        ),
      ),
    );
  }

  Future<void> _payment({bool settle = false, InvoicePayment? reversal}) async {
    final id = widget.data.invoiceId;
    final copy = await Navigator.push<String>(
      context,
      MaterialPageRoute<String>(
        builder: (_) => InvoicePaymentEditor(
          invoiceId: id,
          settle: settle,
          reversal: reversal,
        ),
      ),
    );
    if (!mounted) return;
    ref.invalidate(invoicePaymentsProvider(id));
    if (copy != null) await _open(copy);
  }

  Future<void> _correction(bool replace) async {
    final id = widget.data.invoiceId;
    final result = await Navigator.push<InvoiceLifecycleResult>(
      context,
      MaterialPageRoute<InvoiceLifecycleResult>(
        builder: (_) => InvoiceLifecycleEditor(invoiceId: id, replace: replace),
      ),
    );
    if (!mounted || result == null) return;
    ref.invalidate(invoicePaymentsProvider(id));
    if (result.replacementId != null) {
      await _related(result.replacementId!);
    } else {
      await _open('void-$id');
    }
  }

  Future<void> _related(String id) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) => InvoiceDetailScreen(invoiceId: id),
      ),
    );
    if (mounted) ref.invalidate(invoicePaymentsProvider(widget.data.invoiceId));
  }

  Widget _amount(String title, String minor, {bool prominent = false}) =>
      Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 4),
            SelectableText(
              '${widget.data.currency} ${money(minor, widget.data.digits)}',
              style: prominent
                  ? Theme.of(context).textTheme.headlineSmall
                  : Theme.of(context).textTheme.titleMedium,
            ),
          ],
        ),
      );
  @override
  Widget build(BuildContext context) {
    final data = widget.data;
    final eligible = !_pendingCopy && !widget.refreshFailed;
    final voided = data.status == 'VOID';
    final correctable = !voided && (data.due != '0' || data.total == '0');
    return PopScope(
      canPop: !_pendingCopy,
      child: Scaffold(
        appBar: AppBar(
          title: Text(data.number),
          actions: [
            IconButton(
              tooltip: AppMessage.refreshInvoiceDetails.of(context),
              onPressed: _pendingCopy
                  ? null
                  : () =>
                        ref.invalidate(invoicePaymentsProvider(data.invoiceId)),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: ListView(
          padding: EdgeInsets.fromLTRB(
            24,
            24,
            24,
            24 + MediaQuery.viewPaddingOf(context).bottom,
          ),
          children: [
            Text(data.jobName, style: Theme.of(context).textTheme.titleLarge),
            if (data.customer.isNotEmpty) Text(data.customer),
            if (data.jobDeleted)
              Text(AppMessage.invoiceDeletedJobRetained.of(context)),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerLeft,
              child: Chip(
                label: Text(invoicePaymentStatus(data.status).of(context)),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _amount(AppMessage.invoiceTotal.of(context), data.total),
                    if (data.cancellation == null)
                      _amount(
                        AppMessage.invoicePaidToDate.of(context),
                        data.paid,
                      )
                    else ...[
                      _amount(
                        AppMessage.invoiceCreditTransferred.of(context),
                        data.cancellation!.credit,
                      ),
                      _amount(
                        AppMessage.invoiceCancelledBalance.of(context),
                        data.cancellation!.cancelled,
                      ),
                    ],
                    _amount(
                      AppMessage.invoiceBalanceDue.of(context),
                      data.due,
                      prominent: true,
                    ),
                    if (data.collectedTax != null)
                      Text(
                        AppMessage.invoiceCollectedTaxIncluded.format(context, {
                          'currency': data.currency,
                          'amount': money(data.collectedTax, data.digits),
                        }),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ),
            TextButton.icon(
              onPressed: eligible ? () => _open() : null,
              icon: const Icon(Icons.picture_as_pdf_outlined),
              label: Text(AppMessage.viewOriginalPdf.of(context)),
            ),
            const SizedBox(height: 16),
            if (correctable)
              ExpansionTile(
                title: Text(AppMessage.correctInvoice.of(context)),
                children: [
                  if (data.paid == '0')
                    TextButton.icon(
                      onPressed: eligible ? () => _correction(false) : null,
                      style: TextButton.styleFrom(
                        foregroundColor: Theme.of(context).colorScheme.error,
                      ),
                      icon: const Icon(Icons.block_outlined),
                      label: Text(AppMessage.voidUnpaidInvoice.of(context)),
                    ),
                  TextButton.icon(
                    onPressed: eligible ? () => _correction(true) : null,
                    icon: const Icon(Icons.edit_outlined),
                    label: Text(AppMessage.voidAndReplaceInvoice.of(context)),
                  ),
                ],
              ),
            const SizedBox(height: 16),
            if (!voided && data.due != '0')
              Wrap(
                spacing: 12,
                runSpacing: 8,
                children: [
                  FilledButton.icon(
                    onPressed: eligible ? () => _payment() : null,
                    icon: const Icon(Icons.add_card_outlined),
                    label: Text(AppMessage.recordInvoicePayment.of(context)),
                  ),
                  OutlinedButton.icon(
                    onPressed: eligible ? () => _payment(settle: true) : null,
                    icon: const Icon(Icons.check_circle_outline),
                    label: Text(AppMessage.markInvoicePaid.of(context)),
                  ),
                ],
              ),
            const SizedBox(height: 16),
            BusinessCommand(
              key: const ValueKey('invoice-balance-copy-command'),
              label: AppMessage.sendLatestInvoiceCopy.of(context),
              enabled: eligible && !voided,
              onPendingChanged: (value) {
                if (mounted) setState(() => _pendingCopy = value);
              },
              create: () {
                final id = newBusinessId(),
                    token = newBusinessId(),
                    version = data.version;
                _copyToken = token;
                final api = ref.read(paymentApiProvider);
                return () =>
                    api.balanceCopy(id, version, token, data.invoiceId);
              },
              onCommitted: () {
                ref.invalidate(invoicePaymentsProvider(data.invoiceId));
                _open('balance-$_copyToken', true);
              },
            ),
            const SizedBox(height: 8),
            Text(
              voided
                  ? AppMessage.invoiceVoidNotice.of(context)
                  : AppMessage.invoiceCurrentCopyHelp.of(context),
            ),
            if (voided) ...[
              OutlinedButton.icon(
                onPressed: eligible
                    ? () => _open('void-${data.invoiceId}', true)
                    : null,
                icon: const Icon(Icons.description_outlined),
                label: Text(AppMessage.emailCancellationCopy.of(context)),
              ),
              if (data.cancellation?.replacementId != null)
                TextButton(
                  onPressed: eligible
                      ? () => _related(data.cancellation!.replacementId!)
                      : null,
                  child: Text(AppMessage.openReplacementInvoice.of(context)),
                ),
              if (data.cancellation != null)
                ExpansionTile(
                  title: Text(
                    AppMessage.invoiceCancellationHistory.of(context),
                  ),
                  children: [
                    Text(data.cancellation!.createdLabel),
                    Text(
                      AppMessage.invoiceCancellationReason.format(context, {
                        'reason': data.cancellation!.reason,
                      }),
                    ),
                  ],
                ),
            ],
            if (data.replacesInvoiceId != null)
              TextButton(
                onPressed: eligible
                    ? () => _related(data.replacesInvoiceId!)
                    : null,
                child: Text(AppMessage.openOriginalInvoice.of(context)),
              ),
            if (data.accountingEmail.isEmpty)
              Text(AppMessage.invoiceMissingAccountingEmail.of(context))
            else
              Text(
                data.accountingEmail.toLowerCase() ==
                        data.customerEmail.toLowerCase()
                    ? AppMessage.invoiceAccountingEmailMatches.of(context)
                    : AppMessage.invoiceAccountingEmailCc.format(context, {
                        'email': data.accountingEmail,
                      }),
              ),
            if (widget.refreshFailed)
              Text(AppMessage.invoiceDetailsRefreshFailed.of(context)),
            if (data.legacy) ...[
              const SizedBox(height: 20),
              Text(
                data.jobDeleted
                    ? AppMessage.invoiceHistoricalSourcesUnavailable.of(context)
                    : AppMessage.invoiceBilledWorkReview.of(context),
              ),
              if (!data.jobDeleted)
                TextButton.icon(
                  onPressed: eligible
                      ? () => Navigator.push<bool>(
                          context,
                          MaterialPageRoute<bool>(
                            builder: (_) =>
                                LegacyInvoiceScreen(invoiceId: data.invoiceId),
                          ),
                        )
                      : null,
                  icon: const Icon(Icons.fact_check_outlined),
                  label: Text(AppMessage.reviewBilledWork.of(context)),
                ),
            ],
            const SizedBox(height: 28),
            Text(
              AppMessage.invoicePaymentHistory.of(context),
              style: Theme.of(context).textTheme.titleLarge,
            ),
            if (data.payments.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(AppMessage.invoiceNoPayments.of(context)),
              ),
            for (final payment in data.payments)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        '${payment.currency} ${money(payment.amount, data.digits)}',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(payment.receivedLabel),
                      if (payment.method.isNotEmpty ||
                          payment.reference.isNotEmpty)
                        Text(
                          [
                            payment.method,
                            payment.reference,
                          ].where((v) => v.isNotEmpty).join(' · '),
                        ),
                      if (payment.reversalId != null)
                        Text(AppMessage.invoicePaymentReversed.of(context)),
                      if (payment.note.isNotEmpty ||
                          payment.reversalNote != null)
                        ExpansionTile(
                          tilePadding: EdgeInsets.zero,
                          title: Text(
                            AppMessage.invoicePaymentNotes.of(context),
                          ),
                          children: [
                            if (payment.note.isNotEmpty) Text(payment.note),
                            if (payment.reversalNote != null)
                              Text(
                                AppMessage.invoicePaymentCorrectionNote.format(
                                  context,
                                  {'note': payment.reversalNote!},
                                ),
                              ),
                          ],
                        ),
                      Wrap(
                        spacing: 12,
                        children: [
                          TextButton(
                            onPressed: eligible
                                ? () => _open(
                                    'receipt-${payment.id}',
                                    false,
                                    payment.originalInvoiceId,
                                  )
                                : null,
                            child: Text(
                              AppMessage.openPaymentReceipt.of(context),
                            ),
                          ),
                          if (payment.allocatedInvoiceId != data.invoiceId)
                            TextButton(
                              onPressed: eligible
                                  ? () => _related(payment.allocatedInvoiceId)
                                  : null,
                              child: Text(
                                AppMessage.openCurrentInvoice.of(context),
                              ),
                            ),
                          if (!voided &&
                              payment.reversalId == null &&
                              payment.allocatedInvoiceId == data.invoiceId)
                            TextButton(
                              onPressed: eligible
                                  ? () => _payment(reversal: payment)
                                  : null,
                              child: Text(
                                AppMessage.correctInvoicePayment.of(context),
                              ),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 24),
            Text(
              AppMessage.invoiceSavedCopies.of(context),
              style: Theme.of(context).textTheme.titleLarge,
            ),
            if (data.copies.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(AppMessage.invoiceNoSavedCopies.of(context)),
              ),
            for (final copy in data.copies)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.description_outlined),
                title: Text(switch (copy.kind) {
                  'RECEIPT' => AppMessage.invoicePaymentReceipt.of(context),
                  'CORRECTION' => AppMessage.invoicePaymentCorrection.of(
                    context,
                  ),
                  'VOID' => AppMessage.invoiceCancellationCopy.of(context),
                  _ => AppMessage.invoiceBalanceCopy.of(context),
                }),
                subtitle: Text(
                  [
                    copy.createdLabel,
                    AppMessage.invoiceCopyBalanceSummary.format(context, {
                      'paidLabel':
                          (copy.kind == 'VOID'
                                  ? AppMessage.invoiceCreditTransferred
                                  : AppMessage.invoiceCopyPaid)
                              .of(context),
                      'paid': money(copy.paid, data.digits),
                      'dueLabel': AppMessage.invoiceCopyDue.of(context),
                      'due': money(copy.due, data.digits),
                      'currency': data.currency,
                    }),
                  ].join('\n'),
                ),
                trailing: const Icon(Icons.chevron_right),
                onTap: eligible ? () => _open(copy.id) : null,
              ),
          ],
        ),
      ),
    );
  }
}
