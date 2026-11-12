import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'invoice_screen.dart';
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
      skipLoadingOnReload: true, skipError: true,
      loading: () => Scaffold(appBar: AppBar(title: const Text('Invoice')), body: const Center(child: CircularProgressIndicator())),
      error: (_, _) => Scaffold(appBar: AppBar(title: const Text('Invoice')), body: Center(child: TextButton(
        onPressed: () => ref.invalidate(invoicePaymentsProvider(invoiceId)), child: const Text('Retry invoice details'),
      ))),
      data: (data) => _InvoiceDetail(key: ValueKey(invoiceId), data: data, refreshFailed: result.hasError),
    );
  }
}

class _InvoiceDetail extends ConsumerStatefulWidget {
  const _InvoiceDetail({super.key, required this.data, required this.refreshFailed});
  final InvoicePayments data;
  final bool refreshFailed;
  @override
  ConsumerState<_InvoiceDetail> createState() => _InvoiceDetailState();
}

class _InvoiceDetailState extends ConsumerState<_InvoiceDetail> {
  String? _copyToken;
  bool _pendingCopy = false;
  Future<void> _open([String? copyId, bool compose = false]) async {
    await Navigator.push<void>(context, MaterialPageRoute<void>(builder: (_) => InvoiceScreen(invoiceId: widget.data.invoiceId, copyId: copyId, composeOnOpen: compose)));
  }
  Future<void> _payment({bool settle = false, InvoicePayment? reversal}) async {
    final id = widget.data.invoiceId;
    final copy = await Navigator.push<String>(context, MaterialPageRoute<String>(builder: (_) => InvoicePaymentEditor(invoiceId: id, settle: settle, reversal: reversal)));
    if (!mounted) return;
    ref.invalidate(invoicePaymentsProvider(id));
    if (copy != null) await _open(copy);
  }
  Widget _amount(String title, String minor, {bool prominent = false}) => Padding(
    padding: const EdgeInsets.only(bottom: 12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: Theme.of(context).textTheme.labelLarge),
      const SizedBox(height: 4),
      SelectableText('${widget.data.currency} ${money(minor, widget.data.digits)}', style: prominent ? Theme.of(context).textTheme.headlineSmall : Theme.of(context).textTheme.titleMedium),
    ]),
  );
  @override
  Widget build(BuildContext context) {
    final data = widget.data;
    final eligible = !_pendingCopy && !widget.refreshFailed;
    return PopScope(canPop: !_pendingCopy, child: Scaffold(
      appBar: AppBar(title: Text(data.number), actions: [IconButton(tooltip: 'Refresh invoice details', onPressed: _pendingCopy ? null : () => ref.invalidate(invoicePaymentsProvider(data.invoiceId)), icon: const Icon(Icons.refresh))]),
      body: ListView(padding: const EdgeInsets.all(24), children: [
        Text(data.jobName, style: Theme.of(context).textTheme.titleLarge),
        if (data.customer.isNotEmpty) Text(data.customer),
        if (data.jobDeleted) const Text('Job deleted · financial records retained'),
        const SizedBox(height: 12),
        Align(alignment: Alignment.centerLeft, child: Chip(label: Text(invoicePaymentStatus(data.status)))),
        const SizedBox(height: 12),
        Card(child: Padding(padding: const EdgeInsets.all(20), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          _amount('Invoice total', data.total), _amount('Paid to date', data.paid), _amount('Balance due', data.due, prominent: true),
          if (data.collectedTax != null) Text('Collected tax included: ${data.currency} ${money(data.collectedTax, data.digits)}', style: Theme.of(context).textTheme.bodySmall),
        ]))),
        const SizedBox(height: 16),
        if (data.due != '0') Wrap(spacing: 12, runSpacing: 8, children: [
          FilledButton.icon(onPressed: eligible ? () => _payment() : null, icon: const Icon(Icons.add_card_outlined), label: const Text('Record payment')),
          OutlinedButton.icon(onPressed: eligible ? () => _payment(settle: true) : null, icon: const Icon(Icons.check_circle_outline), label: const Text('Mark paid')),
        ]),
        const SizedBox(height: 16),
        BusinessCommand(
          key: const ValueKey('invoice-balance-copy-command'),
          label: 'Send latest copy', enabled: eligible,
          onPendingChanged: (value) { if (mounted) setState(() => _pendingCopy = value); },
          create: () {
            final id = newBusinessId(), token = newBusinessId(), version = data.version;
            _copyToken = token;
            final api = ref.read(paymentApiProvider);
            return () => api.balanceCopy(id, version, token, data.invoiceId);
          },
          onCommitted: () { ref.invalidate(invoicePaymentsProvider(data.invoiceId)); _open('balance-$_copyToken', true); },
        ),
        const SizedBox(height: 8),
        const Text('Prepare the current balance and open an email draft with its PDF attached.'),
        TextButton.icon(onPressed: eligible ? () => _open() : null, icon: const Icon(Icons.picture_as_pdf_outlined), label: const Text('View original PDF')),
        if (data.accountingEmail.isEmpty) const Text('No accounting copy address is configured. Add a company or accounts receivable email in Settings.')
        else Text(data.accountingEmail.toLowerCase() == data.customerEmail.toLowerCase()
          ? 'The accounting address is already the customer recipient.' : 'Email copies CC ${data.accountingEmail}.'),
        if (widget.refreshFailed) const Text('Refresh failed. Saved details are shown; refresh before making changes.'),
        if (data.legacy) ...[
          const SizedBox(height: 20),
          Text(data.jobDeleted ? 'Historical work links are unavailable for this deleted job.' : 'Billed work on this historical invoice needs review.'),
          if (!data.jobDeleted) TextButton.icon(onPressed: eligible ? () => Navigator.push<bool>(context, MaterialPageRoute<bool>(builder: (_) => LegacyInvoiceScreen(invoiceId: data.invoiceId))) : null,
            icon: const Icon(Icons.fact_check_outlined), label: const Text('Review billed work')),
        ],
        const SizedBox(height: 28),
        Text('Payment history', style: Theme.of(context).textTheme.titleLarge),
        if (data.payments.isEmpty) const Padding(padding: EdgeInsets.symmetric(vertical: 16), child: Text('No payments recorded.')),
        for (final payment in data.payments) Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('${payment.currency} ${money(payment.amount, data.digits)}', style: Theme.of(context).textTheme.titleMedium),
          Text(payment.receivedLabel),
          if (payment.method.isNotEmpty || payment.reference.isNotEmpty) Text([payment.method, payment.reference].where((v) => v.isNotEmpty).join(' · ')),
          if (payment.reversalId != null) const Text('Reversed · original receipt kept'),
          if (payment.note.isNotEmpty || payment.reversalNote != null) ExpansionTile(tilePadding: EdgeInsets.zero, title: const Text('Internal notes'), children: [
            if (payment.note.isNotEmpty) Text(payment.note),
            if (payment.reversalNote != null) Text('Correction: ${payment.reversalNote}'),
          ]),
          Wrap(spacing: 12, children: [
            TextButton(onPressed: eligible ? () => _open('receipt-${payment.id}') : null, child: const Text('Open receipt')),
            if (payment.reversalId == null) TextButton(onPressed: eligible ? () => _payment(reversal: payment) : null, child: const Text('Correct payment')),
          ]),
        ]))),
        const SizedBox(height: 24),
        Text('Saved copies', style: Theme.of(context).textTheme.titleLarge),
        if (data.copies.isEmpty) const Padding(padding: EdgeInsets.symmetric(vertical: 16), child: Text('Receipts and balance copies will appear here.')),
        for (final copy in data.copies) ListTile(contentPadding: EdgeInsets.zero, leading: const Icon(Icons.description_outlined),
          title: Text(switch (copy.kind) { 'RECEIPT' => 'Payment receipt', 'CORRECTION' => 'Payment correction', _ => 'Balance copy' }),
          subtitle: Text('${copy.createdLabel}\nPaid ${money(copy.paid, data.digits)} · Due ${money(copy.due, data.digits)} ${data.currency}'),
          trailing: const Icon(Icons.chevron_right), onTap: eligible ? () => _open(copy.id) : null),
      ]),
    ));
  }
}
