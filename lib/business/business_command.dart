import 'package:flutter/material.dart';

import '../tracking/timer_api.dart';

/// Captures one immutable native command for response-loss retries. Keeps its
/// containing editor open while the outcome is unknown.
class BusinessCommand extends StatefulWidget {
  const BusinessCommand({
    super.key,
    required this.create,
    required this.onCommitted,
    required this.label,
    this.enabled = true,
    this.onPendingChanged,
  });
  final Future<TimerOutcome> Function() Function() create;
  final VoidCallback onCommitted;
  final String label;
  final bool enabled;
  final ValueChanged<bool>? onPendingChanged;
  @override
  State<BusinessCommand> createState() => _BusinessCommandState();
}

class _BusinessCommandState extends State<BusinessCommand>
    with AutomaticKeepAliveClientMixin<BusinessCommand> {
  Future<TimerOutcome> Function()? _pending;
  // Lazy form lists may evict an offscreen button. Retain unknown operations
  // until the original command has a known outcome and can safely be released.
  @override
  bool get wantKeepAlive => _pending != null;
  bool _busy = false;
  String? _message;
  Future<void> _submit() async {
    if (_busy) return;
    try {
      _pending ??= widget.create();
      updateKeepAlive();
      widget.onPendingChanged?.call(true);
      setState(() {
        _busy = true;
        _message = null;
      });
      final result = await _pending!();
      if (!mounted) return;
      setState(() {
        _pending = null;
        _busy = false;
        _message = result.committed ? null : _conflict(result.conflict);
      });
      updateKeepAlive();
      widget.onPendingChanged?.call(false);
      if (result.committed) widget.onCommitted();
    } on FormatException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _message = e.message;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _busy = false;
          _message = 'The result is unknown. Retry to check the same operation before leaving.';
        });
      }
    }
  }

  String _conflict(String? value) => switch (value) {
    'INVALID_MONEY' => 'Enter an exact nonnegative price within the supported length. Your draft is kept.',
    'INVALID_CURRENCY' => 'Choose a supported currency. Your draft is kept.',
    'CUSTOMER_UNAVAILABLE' => 'This customer is unavailable. Choose another customer or clear the selection.',
    'EMAIL_TOO_LARGE' => 'The expanded email is too long. Shorten the email template or document details.',
    'INVALID_QUOTE_SCOPE' => 'Review selected lines, task names and your private scope note. Your draft is kept.',
    'QUOTE_UNAVAILABLE' || 'QUOTE_LINE_UNAVAILABLE' => 'This issued quote or line is unavailable. Refresh its revision before planning.',
    'QUOTE_ACCEPTANCE_REQUIRED' => 'Record explicit local acceptance for this revision before planning work.',
    'QUOTE_HISTORICAL_REVIEW_REQUIRED' => 'Review and acknowledge the historical accepted revision before planning.',
    'QUOTE_CURRENCY_PRECISION_CHANGED' => 'The currency precision changed since issue. Review a new quote revision before planning.',
    'QUOTE_JOB_MODE_MISMATCH' || 'QUOTE_SCOPE_SPLIT_REQUIRED' => 'Choose a compatible job for each fixed-price or agreed hourly-rate group.',
    'QUOTE_JOB_REVIEW_REQUIRED' => 'Review the existing job’s customer, tax and hourly rate differences before applying.',
    'QUOTE_SCOPE_ALREADY_MAPPED' || 'QUOTE_TASK_ALREADY_MAPPED' => 'This line or task already belongs to a retained quote plan. Refresh and reuse its existing mapping.',
    'QUOTE_SCOPE_KIND_CHANGED' => 'A retained line cannot change its billing kind. Add a new quote line for the changed scope.',
    'QUOTE_SCOPE_OPEN_COLUMN_REQUIRED' => 'Add an open board column before creating planning tasks.',
    'TASK_UNAVAILABLE' => 'This retained task was removed. Choose existing work or add a new quote line; deleted identities cannot be recreated.',
    'JOB_ID_EXISTS' || 'TASK_ID_EXISTS' => 'This planning identity already exists. Refresh the retained plan before applying.',
    'INVALID_QUOTE' || 'INVALID_QUOTE_LINE' =>
      'Review the quote title and line descriptions. Your draft is kept.',
    'INVALID_QUOTE_DATE' => 'Enter a real validity date as YYYY-MM-DD, or leave it blank. Your draft is kept.',
    'QUOTE_TOO_LARGE' => 'This quote is too large. Shorten its details or split its scope into separate quotes.',
    'QUOTE_CONFIRMATION_REQUIRED' =>
      'Review the quote before confirming issue.',
    'QUOTE_DRAFT_UNAVAILABLE' => 'This draft was already issued or is no longer available. Refresh the quote.',
    'QUOTE_REVISION_CHANGED' => 'A newer revision exists. Refresh and review it before recording a decision.',
    'QUOTE_ALREADY_DECIDED' => 'This revision already has a local decision. Its recorded history is retained.',
    'QUOTE_SEQUENCE_EXHAUSTED' || 'QUOTE_REVISION_EXHAUSTED' =>
      'The quote numbering limit was reached. Existing documents are retained.',
    'INVALID_QUOTE_DECISION' => 'Confirm the revision and add a note describing the local acceptance or decline.',
    'STALE_STATE' => 'Data changed. Your draft is kept. Refresh the details, review the latest values and save again.',
    'OVERLAP' => 'This interval overlaps work in this or another job. Adjust the start or end time.',
    'INVALID_BOUNDARY' => 'End must be after start with a valid duration.',
    'INVALID_TIMEZONE' =>
      'Check the timezone and choose the intended UTC offset.',
    'WIDGET_UNAVAILABLE' => 'This widget was removed. Close setup and add a new widget from your launcher.',
    'SESSION_UNAVAILABLE' => 'This work entry was removed. Your draft is kept.',
    'STOP_SESSION_BEFORE_EDIT' => 'Clock out before changing this work entry.',
    'CURRENCY_MISMATCH' =>
      'The template uses another currency. Copy without its price.',
    'DESTINATION_REQUIRED' => 'Choose a destination for the tasks first.',
    'STOP_JOB_BEFORE_ARCHIVE' ||
    'STOP_JOB_BEFORE_DELETE' => 'Clock out of this job before continuing.',
    'ARCHIVE_RETENTION_REQUIRED' =>
      'Keep the job archived for 30 continuous days before deleting it.',
    'SITE_CAPACITY_EXCEEDED' => 'Restoring would exceed 100 enabled worksites. Disable other sites before restoring.',
    'HOURLY_RATE_REQUIRED' => 'Set a default hourly rate or a job override before preparing this invoice.',
    'TASK_PRICES_REQUIRED' => 'Set prices for the selected completed billable tasks before preparing this invoice.',
    'INVOICE_TOO_LARGE' => 'Reduce the date range or shorten the invoice/email text, then try again. No new invoice number or work reservation was created.',
    'NO_BILLABLE_ITEMS' => 'No unbilled finalized work or completed billable tasks remain in these dates. Previously invoiced work is excluded.',
    'LEGACY_INVOICE_REVIEW_REQUIRED' => 'Review the billed work on this job’s older invoices before preparing another invoice.',
    'INVOICED_SESSION_BOUNDARY' => 'This edit would move already invoiced work. Keep billed times within the entry and review the original invoice before changing them.',
    'INVALID_TASK_QUANTITY' => 'Enter a quantity greater than 0, with up to four decimal places. Your draft is kept.',
    'INVALID_TAX_RATE' => 'Enter a tax percentage from 0 to 100, with up to four decimal places. Your draft is kept.',
    'LEGACY_CONFIRMATION_REQUIRED' =>
      'Confirm that you checked the covered work against the original invoice.',
    'LEGACY_REVIEW_NOTE_REQUIRED' => 'Add a review note of 10 to 4000 characters explaining which work the original invoice covers.',
    'LEGACY_SOURCE_CHOICE_REQUIRED' => 'Select covered tasks or time, or explicitly confirm there are no source records to link.',
    'INVOICE_SOURCE_UNAVAILABLE' => 'A selected task is no longer available in this job. Refresh and review your selection.',
    'INVOICE_SOURCE_ALREADY_CLAIMED' => 'Some selected work is already reserved by an invoice. Refresh and review its existing invoice link.',
    'INVALID_COVERED_TIME' => 'Review covered time boundaries and timezones. Ranges must be positive, non-overlapping and at most 3660 days each.',
    'INVALID_INVOICE_SOURCES' => 'Review the selected tasks and time ranges. Repeated sources are not allowed.',
    'INVOICE_ALREADY_RECONCILED' => 'This invoice’s source coverage was already reviewed. Return to the invoice list and refresh.',
    'INVOICE_UNAVAILABLE' =>
      'This invoice is unavailable. Refresh the invoice list.',
    'INVALID_LOGO' =>
      'Choose a valid PNG, JPEG or WebP logo within the displayed limits.',
    'LOGO_IMPORT_EXPIRED' =>
      'The temporary image copy is unavailable. Choose the logo again.',
    'CUSTOMER_IN_USE' => 'This customer is assigned to a job or invoice. Archive them to keep the contact details.',
    'CUSTOMER_ARCHIVED' =>
      'Choose an active customer or unarchive this customer first.',
    'INVALID_EMAIL_SUBJECT' => 'The email subject template is invalid. Open Settings and correct its placeholders or line breaks.',
    'INVALID_EMAIL_BODY' => 'The email body template is invalid. Open Settings and correct its placeholders.',
    'INVALID_ACCOUNTS_RECEIVABLE_EMAIL' => 'Enter one valid accounts receivable email address in Settings, or leave it blank to use the company email.',
    'INVALID_PAYMENT_AMOUNT' => 'Enter a positive amount within this invoice’s currency precision. Your draft is kept.',
    'PAYMENT_EXCEEDS_BALANCE' => 'This exceeds the remaining balance. Refresh the invoice and review the payment amount.',
    'PAYMENT_CURRENCY_MISMATCH' => 'Use the invoice’s original currency. Settings changes never convert its balance.',
    'INVALID_PAYMENT' => 'Review the received date, timezone and field lengths. A payment cannot be dated in the future.',
    'INVALID_PAYMENT_REVERSAL' => 'Add a correction reason of at least 10 characters and review its date and timezone.',
    'REVERSAL_BEFORE_PAYMENT' =>
      'The correction date cannot be before the payment was received.',
    'PAYMENT_ALREADY_REVERSED' =>
      'This payment was already corrected. Refresh its history.',
    'PAYMENT_UNAVAILABLE_OR_MOVED' => 'This payment is unavailable here or was credited to a replacement invoice. Refresh and open its current invoice.',
    'PAYMENT_ID_EXISTS' || 'REVERSAL_ID_EXISTS' || 'INVOICE_COPY_ID_EXISTS' => 'This record already exists. Refresh the invoice to review its saved history.',
    'INVOICE_VOID' => 'This invoice is void. Open its cancellation copy or replacement; no payment is due here.',
    'PAID_INVOICE_CANNOT_VOID' => 'A fully paid invoice cannot be voided or replaced. Its receipts remain in history.',
    'PARTIAL_INVOICE_REQUIRES_REPLACEMENT' => 'Use Void & replace so the existing payment credit moves to a new invoice.',
    'REPLACEMENT_BELOW_CREDIT' => 'The replacement total cannot be less than the existing payment credit. Your draft is kept.',
    'INVOICE_VOID_CONFIRMATION_REQUIRED' =>
      'Review and confirm the invoice correction before continuing.',
    'INVOICE_VOID_REASON_REQUIRED' => 'Explain the correction in 10 to 4000 characters. The reason stays internal.',
    'INVALID_REPLACEMENT_LINES' ||
    'INVALID_REPLACEMENT_AMOUNT' ||
    'INVALID_REPLACEMENT' => 'Review the included lines, quantities and exact prices. New work must come from actual unbilled records.',
    'INVALID_RELEASED_CLAIMS' || 'INVALID_REPLACEMENT_SOURCES' => 'Some work reservations changed. Refresh and review the work to retain or release.',
    'INVALID_INVOICE_VOID' =>
      'Refresh the invoice and review its correction details.',
    'INVALID_SETTINGS' => 'Check company email, amounts and email template placeholders. Subjects cannot contain line breaks.',
    'INVALID_CUSTOMER' =>
      'Enter a customer name and a valid email, or leave email blank.',
    'INVALID_JOB' => 'Check the job name, rate and customer email.',
    'JOB_UNAVAILABLE' ||
    'JOB_ARCHIVED' => 'This job is unavailable for editing.',
    _ => 'The change could not be applied. Your draft is kept. Refresh the details and try again.',
  };
  @override
  Widget build(BuildContext context) {
    super.build(context);
    return PopScope(
      canPop: _pending == null,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_message != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(
                _message!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          FilledButton(
            onPressed: _busy || (!widget.enabled && _pending == null)
                ? null
                : _submit,
            child: _busy
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(
                    _pending != null ? 'Retry same operation' : widget.label,
                  ),
          ),
        ],
      ),
    );
  }
}
