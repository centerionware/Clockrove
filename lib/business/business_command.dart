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
    'STALE_STATE' => 'This job changed. Your draft is kept. Review the latest data and save again.',
    'OVERLAP' => 'This interval overlaps work in this or another job. Adjust the start or end time.',
    'INVALID_BOUNDARY' => 'End must be after start with a valid duration.',
    'INVALID_TIMEZONE' =>
      'Check the timezone and choose the intended UTC offset.',
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
    'NO_BILLABLE_ITEMS' =>
      'Choose dates with finalized work or completed billable tasks.',
    'INVALID_LOGO' =>
      'Choose a valid PNG, JPEG or WebP logo within the displayed limits.',
    'LOGO_IMPORT_EXPIRED' =>
      'The temporary image copy is unavailable. Choose the logo again.',
    'CUSTOMER_IN_USE' => 'This customer is assigned to a job or invoice. Archive them to keep the contact details.',
    'CUSTOMER_ARCHIVED' =>
      'Choose an active customer or unarchive this customer first.',
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
              : Text(_pending != null ? 'Retry same operation' : widget.label),
        ),
      ],
    ),
  );
  }
}
