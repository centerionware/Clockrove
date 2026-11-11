import 'package:flutter/material.dart';
import '../tracking/timer_api.dart';

/// Captures one immutable native command for response-loss retries. Keeps its
/// containing editor open while the outcome is unknown.
class BusinessCommand extends StatefulWidget {
  const BusinessCommand({super.key, required this.create, required this.onCommitted, required this.label, this.enabled = true});
  final Future<TimerOutcome> Function() Function() create;
  final VoidCallback onCommitted;
  final String label;
  final bool enabled;
  @override
  State<BusinessCommand> createState() => _BusinessCommandState();
}
class _BusinessCommandState extends State<BusinessCommand> {
  Future<TimerOutcome> Function()? _pending;
  bool _busy = false;
  String? _message;
  Future<void> _submit() async {
    if (_busy) return;
    try {
      _pending ??= widget.create();
      setState(() { _busy = true; _message = null; });
      final result = await _pending!();
      if (!mounted) return;
      setState(() { _pending = null; _busy = false; _message = result.committed ? null : _conflict(result.conflict); });
      if (result.committed) widget.onCommitted();
    } on FormatException catch (e) {
      if (mounted) setState(() { _busy = false; _message = e.message; });
    } catch (_) {
      if (mounted) setState(() { _busy = false; _message = 'The result is unknown. Retry to check the same operation before leaving.'; });
    }
  }
  String _conflict(String? value) => switch (value) {
    'STALE_STATE' => 'This job changed. Your draft is kept. Review the latest data and save again.',
    'CURRENCY_MISMATCH' => 'The template uses another currency. Copy without its price.',
    'DESTINATION_REQUIRED' => 'Choose a destination for the tasks first.',
    'JOB_UNAVAILABLE' || 'JOB_ARCHIVED' => 'This job is unavailable for editing.',
    _ => 'The change could not be applied ($value). Your draft is kept.',
  };
  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _pending == null,
    child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (_message != null) Padding(padding: const EdgeInsets.only(bottom: 12), child: Text(_message!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
      FilledButton(onPressed: _busy || (!widget.enabled && _pending == null) ? null : _submit,
        child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(_pending != null ? 'Retry same operation' : widget.label)),
    ]),
  );
}
