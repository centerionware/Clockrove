import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import '../tracking/timer_api.dart';
import 'settings_validation_messages.dart';

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
  String Function(BuildContext)? _message;
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
        _message = result.committed
            ? null
            : (context) => _conflict(context, result.conflict);
      });
      updateKeepAlive();
      widget.onPendingChanged?.call(false);
      if (result.committed) widget.onCommitted();
    } on FormatException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _message = (context) => localizedBusinessFormatError(context, e);
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _busy = false;
          _message = AppMessage.commandResultUnknown.of;
        });
      }
    }
  }

  String _conflict(BuildContext context, String? value) => switch (value) {
    'INVALID_MONEY' => AppMessage.commandChangeFailed.of(context),
    'INVALID_CURRENCY' => AppMessage.commandInvalidCurrency.of(context),
    'CUSTOMER_UNAVAILABLE' => AppMessage.commandCustomerUnavailable.of(context),
    'EMAIL_TOO_LARGE' => AppMessage.invoiceEmailTooLong.of(context),
    'INVALID_QUOTE_INVOICE' => AppMessage.quoteInvoiceReviewUnavailable.of(
      context,
    ),
    'INVALID_QUOTE_INVOICE_SOURCES' =>
      AppMessage.quoteInvoiceSelectActualWork.of(context),
    'QUOTE_INVOICE_MAPPING_REQUIRED' =>
      AppMessage.quoteInvoiceMappingRequired.of(context),
    'QUOTE_INVOICE_SOURCES_CHANGED' => AppMessage.quoteInvoiceSourcesChanged.of(
      context,
    ),
    'INVALID_REPORT_RANGE' => AppMessage.quoteInvoiceDateRangeInvalid.of(
      context,
    ),
    'INVALID_QUOTE_SCOPE' => AppMessage.quoteReviewHighlightedFields.of(
      context,
    ),
    'QUOTE_UNAVAILABLE' ||
    'QUOTE_LINE_UNAVAILABLE' => AppMessage.quoteUnavailable.of(context),
    'QUOTE_ACCEPTANCE_REQUIRED' => AppMessage.quoteScopeAcceptanceRequired.of(
      context,
    ),
    'QUOTE_HISTORICAL_REVIEW_REQUIRED' =>
      AppMessage.quoteScopeHistoricalReviewRequired.of(context),
    'QUOTE_CURRENCY_PRECISION_CHANGED' =>
      AppMessage.quoteInvoiceCurrencyMismatch.of(context),
    'QUOTE_JOB_MODE_MISMATCH' || 'QUOTE_SCOPE_SPLIT_REQUIRED' =>
      AppMessage.quoteScopeMultipleGroups.of(context),
    'QUOTE_JOB_REVIEW_REQUIRED' =>
      AppMessage.quoteScopeJobDifferencesReviewRequired.of(context),
    'QUOTE_SCOPE_ALREADY_MAPPED' || 'QUOTE_TASK_ALREADY_MAPPED' =>
      AppMessage.quoteScopeAlreadyLinked.of(context),
    'QUOTE_SCOPE_KIND_CHANGED' => AppMessage.quoteInvoiceScopeMismatch.of(
      context,
    ),
    'QUOTE_SCOPE_OPEN_COLUMN_REQUIRED' => AppMessage.commandChangeFailed.of(
      context,
    ),
    'TASK_UNAVAILABLE' => AppMessage.quoteScopeRetainedTaskRemoved.of(context),
    'JOB_ID_EXISTS' ||
    'TASK_ID_EXISTS' => AppMessage.quoteScopeDistinctTaskIdentities.of(context),
    'INVALID_QUOTE' ||
    'INVALID_QUOTE_LINE' => AppMessage.quoteReviewHighlightedFields.of(context),
    'INVALID_QUOTE_DATE' => AppMessage.quoteInvalidCalendarDate.of(context),
    'QUOTE_TOO_LARGE' => AppMessage.quoteTotalsTooLarge.of(context),
    'QUOTE_CONFIRMATION_REQUIRED' => AppMessage.quoteIssueReviewGuidance.of(
      context,
    ),
    'QUOTE_DRAFT_UNAVAILABLE' => AppMessage.quoteUnavailable.of(context),
    'QUOTE_REVISION_CHANGED' => AppMessage.quoteRevisionReadFailed.of(context),
    'QUOTE_ALREADY_DECIDED' => AppMessage.quoteDecisionRetainedNotice.of(
      context,
    ),
    'QUOTE_SEQUENCE_EXHAUSTED' ||
    'QUOTE_REVISION_EXHAUSTED' => AppMessage.commandChangeFailed.of(context),
    'INVALID_QUOTE_DECISION' =>
      AppMessage.quoteReviewCurrentDecisionRequired.of(context),
    'INVALID_TASK_COMMENT' ||
    'TASK_COMMENT_UNAVAILABLE' => AppMessage.commandChangeFailed.of(context),
    'INVALID_TASK_IMAGE' ||
    'TASK_IMAGE_LIMIT' ||
    'TASK_IMAGE_SELECTION_LIMIT' ||
    'TASK_IMAGE_BATCH_STORAGE_LIMIT' ||
    'TASK_IMAGE_STORAGE_LIMIT' ||
    'TASK_IMAGE_UNAVAILABLE' => AppMessage.commandChangeFailed.of(context),
    'STALE_STATE' => AppMessage.commandStaleState.of(context),
    'OVERLAP' => AppMessage.restoreOverlappingInterval.of(context),
    'INVALID_BOUNDARY' => AppMessage.commandChangeFailed.of(context),
    'INVALID_TIMEZONE' => AppMessage.paymentCheckTimezone.of(context),
    'WIDGET_UNAVAILABLE' => AppMessage.commandWidgetUnavailable.of(context),
    'SESSION_UNAVAILABLE' => AppMessage.commandChangeFailed.of(context),
    'STOP_SESSION_BEFORE_EDIT' => AppMessage.commandChangeFailed.of(context),
    'CURRENCY_MISMATCH' => AppMessage.commandInvalidCurrency.of(context),
    'DESTINATION_REQUIRED' => AppMessage.quoteScopeSelectLines.of(context),
    'STOP_JOB_BEFORE_ARCHIVE' ||
    'STOP_JOB_BEFORE_DELETE' => AppMessage.commandChangeFailed.of(context),
    'ARCHIVE_RETENTION_REQUIRED' => AppMessage.archiveJobWarning.of(context),
    'SITE_CAPACITY_EXCEEDED' => AppMessage.commandChangeFailed.of(context),
    'HOURLY_RATE_REQUIRED' => AppMessage.invoiceAddedHourlyRate.of(context),
    'TASK_PRICES_REQUIRED' => AppMessage.invoiceAddedTaskPrices.of(context),
    'INVOICE_TOO_LARGE' => AppMessage.quoteInvoiceTooLarge.of(context),
    'NO_BILLABLE_ITEMS' => AppMessage.invoiceAddedNoWork.of(context),
    'LEGACY_INVOICE_REVIEW_REQUIRED' => AppMessage.invoiceAddedReviewOlder.of(
      context,
    ),
    'INVOICED_SESSION_BOUNDARY' => AppMessage.invoiceCorrectionStale.of(
      context,
    ),
    'INVALID_TASK_QUANTITY' => AppMessage.quoteQuantityInvalid.of(context),
    'INVALID_TAX_RATE' => AppMessage.validationTaxFormat.of(context),
    'LEGACY_CONFIRMATION_REQUIRED' => AppMessage.legacyConfirmationRequired.of(
      context,
    ),
    'LEGACY_REVIEW_NOTE_REQUIRED' => AppMessage.legacyNoteLength.of(context),
    'LEGACY_SOURCE_CHOICE_REQUIRED' =>
      AppMessage.legacyCoverageSourceRequired.of(context),
    'INVOICE_SOURCE_UNAVAILABLE' => AppMessage.quoteScopeRetainedTaskRemoved.of(
      context,
    ),
    'INVOICE_SOURCE_ALREADY_CLAIMED' => AppMessage.quoteInvoiceSourcesStale.of(
      context,
    ),
    'INVALID_COVERED_TIME' => AppMessage.invoiceCorrectionStale.of(context),
    'INVALID_INVOICE_SOURCES' => AppMessage.quoteInvoiceSourcesStale.of(
      context,
    ),
    'INVOICE_ALREADY_RECONCILED' => AppMessage.invoiceReviewCoverage.of(
      context,
    ),
    'INVOICE_UNAVAILABLE' => AppMessage.commandInvoiceUnavailable.of(context),
    'INVALID_LOGO' ||
    'LOGO_IMPORT_EXPIRED' => AppMessage.commandChangeFailed.of(context),
    'CUSTOMER_IN_USE' => AppMessage.commandChangeFailed.of(context),
    'CUSTOMER_ARCHIVED' => AppMessage.commandCustomerUnavailable.of(context),
    'INVALID_EMAIL_SUBJECT' => AppMessage.commandInvalidEmailSubject.of(
      context,
    ),
    'INVALID_EMAIL_BODY' => AppMessage.commandInvalidEmailBody.of(context),
    'INVALID_ACCOUNTS_RECEIVABLE_EMAIL' => AppMessage.commandInvalidSettings.of(
      context,
    ),
    'INVALID_PAYMENT_AMOUNT' => AppMessage.paymentPositiveAmount.of(context),
    'INVALID_ACTUAL_COST_AMOUNT' => AppMessage.invoiceLinePriceRequired.of(
      context,
    ),
    'INVALID_ACTUAL_COST' => AppMessage.commandChangeFailed.of(context),
    'COST_JOB_UNAVAILABLE' => AppMessage.commandJobUnavailable.of(context),
    'COST_TASK_UNAVAILABLE' ||
    'ACTUAL_COST_UNAVAILABLE' => AppMessage.commandChangeFailed.of(context),
    'PAYMENT_EXCEEDS_BALANCE' => AppMessage.paymentAboveBalance.of(context),
    'PAYMENT_CURRENCY_MISMATCH' => AppMessage.quoteInvoiceCurrencyMismatch.of(
      context,
    ),
    'INVALID_PAYMENT' => AppMessage.paymentConfirmFullDate.of(context),
    'INVALID_PAYMENT_REVERSAL' => AppMessage.paymentCorrectionMinLength.of(
      context,
    ),
    'REVERSAL_BEFORE_PAYMENT' => AppMessage.paymentCheckTimezone.of(context),
    'PAYMENT_ALREADY_REVERSED' => AppMessage.paymentReversalReceiptHelp.of(
      context,
    ),
    'PAYMENT_UNAVAILABLE_OR_MOVED' => AppMessage.paymentBalanceChanged.of(
      context,
    ),
    'PAYMENT_ID_EXISTS' ||
    'REVERSAL_ID_EXISTS' ||
    'INVOICE_COPY_ID_EXISTS' => AppMessage.commandStaleState.of(context),
    'INVOICE_VOID' => AppMessage.invoiceVoidNotice.of(context),
    'PAID_INVOICE_CANNOT_VOID' => AppMessage.invoiceCorrectionUnavailable.of(
      context,
    ),
    'PARTIAL_INVOICE_REQUIRES_REPLACEMENT' =>
      AppMessage.voidAndReplaceInvoice.of(context),
    'REPLACEMENT_BELOW_CREDIT' => AppMessage.invoiceUseRefreshedBalance.of(
      context,
    ),
    'INVOICE_VOID_CONFIRMATION_REQUIRED' =>
      AppMessage.reviewInvoiceCorrection.of(context),
    'INVOICE_VOID_REASON_REQUIRED' =>
      AppMessage.invoiceInternalCorrectionReason.of(context),
    'INVALID_REPLACEMENT_LINES' ||
    'INVALID_REPLACEMENT_AMOUNT' ||
    'INVALID_REPLACEMENT' => AppMessage.invoiceLinePriceRequired.of(context),
    'INVALID_RELEASED_CLAIMS' || 'INVALID_REPLACEMENT_SOURCES' =>
      AppMessage.invoiceCorrectionStale.of(context),
    'INVALID_INVOICE_VOID' => AppMessage.invoiceCorrectionStale.of(context),
    'INVALID_SETTINGS' => AppMessage.commandInvalidSettings.of(context),
    'INVALID_CUSTOMER' => AppMessage.commandCustomerUnavailable.of(context),
    'INVALID_JOB' => AppMessage.commandJobUnavailable.of(context),
    'JOB_UNAVAILABLE' ||
    'JOB_ARCHIVED' => AppMessage.commandJobUnavailable.of(context),
    _ => AppMessage.commandChangeFailed.of(context),
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
                _message!(context),
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
                    _pending != null
                        ? AppMessage.commandRetry.of(context)
                        : widget.label,
                  ),
          ),
        ],
      ),
    );
  }
}
