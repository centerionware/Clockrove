import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/history_api.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'payment_api.dart';

class InvoicePaymentEditor extends ConsumerWidget {
  const InvoicePaymentEditor({
    super.key,
    required this.invoiceId,
    this.settle = false,
    this.reversal,
  });
  final String invoiceId;
  final bool settle;
  final InvoicePayment? reversal;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final result = ref.watch(invoicePaymentsProvider(invoiceId));
    return result.when(
      skipLoadingOnReload: true,
      skipError: true,
      loading: () => Scaffold(
        appBar: AppBar(title: const Text('Payment')),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Scaffold(
        appBar: AppBar(title: const Text('Payment')),
        body: Center(
          child: TextButton(
            onPressed: () => ref.invalidate(invoicePaymentsProvider(invoiceId)),
            child: const Text('Retry invoice balance'),
          ),
        ),
      ),
      data: (data) => _PaymentForm(
        key: ValueKey('$invoiceId/${reversal?.id}/$settle'),
        data: data,
        settle: settle,
        reversal: reversal,
        refreshFailed: result.hasError,
      ),
    );
  }
}

class _PaymentForm extends ConsumerStatefulWidget {
  const _PaymentForm({
    super.key,
    required this.data,
    required this.settle,
    required this.reversal,
    required this.refreshFailed,
  });
  final InvoicePayments data;
  final bool settle, refreshFailed;
  final InvoicePayment? reversal;
  @override
  ConsumerState<_PaymentForm> createState() => _PaymentFormState();
}

class _PaymentFormState extends ConsumerState<_PaymentForm> {
  late final _amount = TextEditingController(
    text: widget.settle ? money(widget.data.due, widget.data.digits) : '',
  );
  final _method = TextEditingController(),
      _reference = TextEditingController(),
      _note = TextEditingController();
  late final _zone = TextEditingController(text: widget.data.timezone);
  late String _local = widget.data.localNow;
  late HistoryTimeChoice? _received = HistoryTimeChoice(
    widget.data.now,
    widget.data.nowOffsetSeconds,
    widget.data.nowLabel,
  );
  String? _amountError, _timeError, _noteError, _token;
  bool _pending = false, _resolving = false;
  bool get _locked => _pending || _resolving;
  bool get _correction => widget.reversal != null;
  @override
  void dispose() {
    for (final controller in [_amount, _method, _reference, _note, _zone]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _resolve(String local) async {
    if (_locked) return;
    final timezone = _zone.text.trim();
    setState(() {
      _resolving = true;
      _received = null;
      _timeError = null;
      _local = local;
    });
    try {
      final choices = await ref
          .read(historyApiProvider)
          .resolve(local, timezone);
      if (!mounted) return;
      HistoryTimeChoice? selected;
      if (choices.length == 1) {
        selected = choices.single;
      } else if (choices.length > 1) {
        selected = await showDialog<HistoryTimeChoice>(
          context: context,
          builder: (context) => SimpleDialog(
            title: const Text('Choose the UTC offset'),
            children: [
              for (final choice in choices)
                SimpleDialogOption(
                  onPressed: () => Navigator.pop(context, choice),
                  child: Text(choice.label),
                ),
            ],
          ),
        );
      }
      if (!mounted) return;
      setState(() {
        _received = selected;
        _timeError = choices.isEmpty
            ? 'This time is skipped in that timezone. Choose another time.'
            : selected == null
            ? 'Choose the intended UTC offset before saving.'
            : null;
      });
    } catch (_) {
      if (mounted) {
        setState(
          () => _timeError =
              'Check the timezone, then confirm the date and time again.',
        );
      }
    } finally {
      if (mounted) setState(() => _resolving = false);
    }
  }

  Future<void> _pick() async {
    if (_locked) return;
    final seed = DateTime.parse(_local);
    final date = await showDatePicker(
      context: context,
      initialDate: DateTime.utc(seed.year, seed.month, seed.day),
      firstDate: DateTime.utc(1),
      lastDate: DateTime.utc(9999, 12, 31),
    );
    if (!mounted || date == null) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: seed.hour, minute: seed.minute),
    );
    if (!mounted || time == null) return;
    final local = DateTime.utc(
      date.year,
      date.month,
      date.day,
      time.hour,
      time.minute,
    ).toIso8601String().replaceFirst(RegExp(r'Z$'), '');
    await _resolve(local);
  }

  Widget _field(
    TextEditingController controller,
    String label,
    int max, {
    int lines = 1,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: TextField(
      controller: controller,
      enabled: !_locked,
      maxLength: max,
      minLines: lines,
      maxLines: lines + 2,
      decoration: InputDecoration(labelText: label),
    ),
  );
  @override
  Widget build(BuildContext context) {
    final data = widget.data;
    final currentPayment = widget.reversal == null
        ? null
        : data.payments.where((p) => p.id == widget.reversal!.id).firstOrNull;
    final available = _correction
        ? currentPayment != null &&
              currentPayment.reversalId == null &&
              currentPayment.allocatedInvoiceId == data.invoiceId &&
              data.status != 'VOID'
        : data.due != '0';
    final calendar = DateTime.parse(_local);
    final localizations = MaterialLocalizations.of(context);
    final dateLabel =
        '${localizations.formatMediumDate(calendar)} · ${localizations.formatTimeOfDay(TimeOfDay(hour: calendar.hour, minute: calendar.minute))}';
    final command = BusinessCommand(
      key: const ValueKey('invoice-payment-command'),
      label: _correction ? 'Confirm reversal' : 'Confirm payment',
      enabled: !_locked && !widget.refreshFailed && available,
      onPendingChanged: (value) {
        if (mounted) setState(() => _pending = value);
      },
      create: () {
        String? minor;
        if (!_correction) {
          try {
            minor = parsePaymentMoney(_amount.text, data.digits);
            if (minor == null || BigInt.parse(minor) <= BigInt.zero) {
              throw const FormatException('Enter an amount greater than zero.');
            }
            if (BigInt.parse(minor) > BigInt.parse(data.due)) {
              throw const FormatException(
                'This exceeds the current balance. Review the amount or refresh the invoice.',
              );
            }
            setState(() => _amountError = null);
          } on FormatException catch (error) {
            setState(() => _amountError = error.message);
            rethrow;
          }
        }
        final received = _received;
        if (received == null) {
          setState(
            () => _timeError =
                'Confirm the date, time and timezone before saving.',
          );
          throw const FormatException('Confirm the highlighted date and time.');
        }
        if (_correction && _note.text.trim().length < 10) {
          setState(
            () => _noteError =
                'Add at least 10 characters explaining the correction.',
          );
          throw const FormatException(
            'Explain why this payment needs correction.',
          );
        }
        final commandId = newBusinessId(),
            token = newBusinessId(),
            version = data.version;
        _token = token;
        final api = ref.read(paymentApiProvider);
        if (_correction) {
          final draft = PaymentReversal(
            token,
            data.invoiceId,
            widget.reversal!.id,
            received.instant,
            _zone.text.trim(),
            _note.text,
          );
          return () => api.reverse(commandId, version, draft);
        }
        final draft = PaymentDraft(
          token,
          data.invoiceId,
          minor!,
          data.currency,
          received.instant,
          _zone.text.trim(),
          _method.text,
          _reference.text,
          _note.text,
        );
        return () => api.record(commandId, version, draft);
      },
      onCommitted: () {
        ref.invalidate(invoicePaymentsProvider(data.invoiceId));
        Navigator.pop(
          context,
          '${_correction ? 'correction' : 'receipt'}-$_token',
        );
      },
    );
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            _correction
                ? 'Correct payment'
                : widget.settle
                ? 'Mark paid'
                : 'Record payment',
          ),
          actions: [
            IconButton(
              tooltip: 'Refresh invoice balance',
              onPressed: _pending
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
            24 + MediaQuery.paddingOf(context).bottom,
          ),
          children: [
            Text(data.number, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(
              'Balance due: ${data.currency} ${money(data.due, data.digits)}',
            ),
            const SizedBox(height: 24),
            if (_correction) ...[
              Text(
                'Reverse the recorded payment of ${data.currency} ${money(widget.reversal!.amount, data.digits)}.',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              const Text(
                'Its original receipt remains in history. Record the reason for correcting this entry.',
              ),
            ] else ...[
              const Text('Record a payment you have received.'),
              const SizedBox(height: 16),
              Semantics(
                identifier: 'invoice-payment-amount',
                child: TextField(
                  controller: _amount,
                  enabled: !_locked,
                  maxLength: 4101,
                  maxLengthEnforcement: MaxLengthEnforcement.none,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  autocorrect: false,
                  enableSuggestions: false,
                  decoration: InputDecoration(
                    labelText: 'Payment amount (${data.currency})',
                    errorText: _amountError,
                    errorMaxLines: 4,
                  ),
                  onChanged: (_) {
                    if (_amountError != null) {
                      setState(() => _amountError = null);
                    }
                  },
                ),
              ),
              _field(_method, 'Payment method (optional)', 80),
              _field(_reference, 'Reference (optional)', 240),
            ],
            const SizedBox(height: 20),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.calendar_month_outlined),
              title: Text(_correction ? 'Correction effective' : 'Received on'),
              subtitle: Text(dateLabel),
              trailing: IconButton(
                tooltip: 'Change payment date and time',
                onPressed: _locked ? null : _pick,
                icon: const Icon(Icons.edit_calendar_outlined),
              ),
            ),
            TextField(
              controller: _zone,
              enabled: !_locked,
              maxLength: 120,
              autocorrect: false,
              decoration: InputDecoration(
                labelText: 'Timezone',
                helperText: 'For example UTC or America/New_York',
                errorText: _timeError,
                errorMaxLines: 4,
              ),
              onChanged: (_) => setState(() {
                _received = null;
                _timeError = null;
              }),
            ),
            if (_received == null)
              TextButton.icon(
                onPressed: _locked ? null : () => _resolve(_local),
                icon: const Icon(Icons.check),
                label: const Text('Confirm date and time'),
              ),
            if (_resolving) const LinearProgressIndicator(),
            const SizedBox(height: 20),
            Semantics(
              identifier: 'invoice-payment-note',
              child: TextField(
                controller: _note,
                enabled: !_locked,
                minLines: 2,
                maxLines: 5,
                maxLength: 4000,
                decoration: InputDecoration(
                  labelText: _correction
                      ? 'Reason for correction'
                      : 'Internal note (optional)',
                  helperText:
                      'Kept in your records; omitted from customer PDFs.',
                  helperMaxLines: 2,
                  errorText: _noteError,
                ),
                onChanged: (_) {
                  if (_noteError != null) setState(() => _noteError = null);
                },
              ),
            ),
            const SizedBox(height: 20),
            const Text('Confirmation saves this entry and opens its receipt.'),
            const SizedBox(height: 12),
            if (widget.refreshFailed)
              const Text(
                'Balance refresh failed. Your draft is kept. Refresh before saving.',
              ),
            if (!available)
              const Text(
                'This balance or payment changed. Review the latest invoice before continuing.',
              ),
            KeyedSubtree(
              key: const ValueKey('invoice-payment-submit'),
              child: _correction
                  ? Theme(
                      data: Theme.of(context).copyWith(
                        colorScheme: Theme.of(context).colorScheme.copyWith(
                          primary: Theme.of(context).colorScheme.error,
                          onPrimary: Theme.of(context).colorScheme.onError,
                        ),
                      ),
                      child: command,
                    )
                  : command,
            ),
          ],
        ),
      ),
    );
  }
}
