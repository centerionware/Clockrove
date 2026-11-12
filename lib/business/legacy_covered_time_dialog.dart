import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/history_api.dart';
import 'legacy_invoice_api.dart';

class LegacyCoveredTimeDialog extends ConsumerStatefulWidget {
  const LegacyCoveredTimeDialog({super.key, required this.timezone, required this.otherTimes, required this.reservedTimes, this.initial});
  final String timezone;
  final List<LegacyCoveredTime> otherTimes;
  final List<LegacyReservedTime> reservedTimes;
  final LegacyCoveredTime? initial;
  @override
  ConsumerState<LegacyCoveredTimeDialog> createState() => _LegacyCoveredTimeDialogState();
}

class _LegacyCoveredTimeDialogState extends ConsumerState<LegacyCoveredTimeDialog> {
  late final _start = TextEditingController(text: widget.initial?.localStart ?? '');
  late final _end = TextEditingController(text: widget.initial?.localEnd ?? '');
  late final _zone = TextEditingController(text: widget.initial?.timezone ?? widget.timezone);
  List<HistoryTimeChoice> _starts = [], _ends = [];
  HistoryTimeChoice? _selectedStart, _selectedEnd;
  bool _resolving = false;
  String? _error;
  @override
  void dispose() { _start.dispose(); _end.dispose(); _zone.dispose(); super.dispose(); }
  void _changed(String _) => setState(() {
    _starts = []; _ends = []; _selectedStart = null; _selectedEnd = null; _error = null;
  });
  Future<void> _pick(TextEditingController field) async {
    final parsed = DateTime.tryParse(field.text);
    final seed = parsed != null && parsed.year >= 1 && parsed.year <= 9999 ? parsed : DateTime.now();
    final date = await showDatePicker(context: context, initialDate: DateTime.utc(seed.year, seed.month, seed.day),
      firstDate: DateTime.utc(1), lastDate: DateTime.utc(9999, 12, 31));
    if (!mounted || date == null) return;
    final time = await showTimePicker(context: context, initialTime: TimeOfDay(hour: seed.hour, minute: seed.minute));
    if (!mounted || time == null) return;
    // A UTC calendar container avoids device-local DST normalization. The native
    // resolver applies the user's selected IANA zone to these wall-time fields.
    field.text = DateTime.utc(date.year, date.month, date.day, time.hour, time.minute, seed.second, seed.millisecond)
        .toIso8601String().replaceFirst(RegExp(r'Z$'), '');
    _changed(field.text);
  }
  Future<void> _resolve() async {
    if (_resolving) return;
    setState(() { _resolving = true; _error = null; _starts = []; _ends = []; _selectedStart = null; _selectedEnd = null; });
    try {
      final api = ref.read(historyApiProvider);
      final values = await Future.wait([api.resolve(_start.text.trim(), _zone.text.trim()), api.resolve(_end.text.trim(), _zone.text.trim())]);
      if (!mounted) return;
      setState(() {
        _starts = values[0]; _ends = values[1];
        final original = widget.initial;
        final keepStart = original != null && _start.text.trim() == original.localStart && _zone.text.trim() == original.timezone ? original.start : null;
        final keepEnd = original != null && _end.text.trim() == original.localEnd && _zone.text.trim() == original.timezone ? original.end : null;
        _selectedStart = _starts.where((v) => v.instant == keepStart).firstOrNull ?? (_starts.length == 1 ? _starts.single : null);
        _selectedEnd = _ends.where((v) => v.instant == keepEnd).firstOrNull ?? (_ends.length == 1 ? _ends.single : null);
        if (_starts.isEmpty || _ends.isEmpty) _error = 'This local time does not exist in that timezone. Choose a time outside the clock change or skipped date.';
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'Check the full date/time, including seconds and three millisecond digits, and the IANA timezone.');
    } finally {
      if (mounted) setState(() => _resolving = false);
    }
  }
  String? get _rangeError {
    final start = _selectedStart?.instant, end = _selectedEnd?.instant;
    if (start == null || end == null) return null;
    if (end <= start) return 'Covered end must be after covered start.';
    if (end - start > 3660 * 86400000) return 'Use a shorter covered time range (at most 3660 days).';
    if (widget.otherTimes.any((time) => time.start < end && time.end > start)) return 'This range overlaps another selected range. Edit or remove that range first.';
    if (widget.reservedTimes.any((value) => value.time.start < end && value.time.end > start)) return 'This time is already reserved by an invoice. Review its existing invoice link.';
    return null;
  }
  Widget _field(TextEditingController controller, String label) => Padding(padding: const EdgeInsets.only(bottom: 16),
    child: TextField(controller: controller, readOnly: _resolving, onChanged: _changed,
      decoration: InputDecoration(labelText: label, hintText: '2026-11-01T09:00:00.000',
        suffixIcon: IconButton(tooltip: 'Choose $label', onPressed: _resolving ? null : () => _pick(controller), icon: const Icon(Icons.calendar_month_outlined)))));
  Widget _choices(String label, List<HistoryTimeChoice> choices, HistoryTimeChoice? selected, ValueChanged<HistoryTimeChoice?> select) {
    if (choices.isEmpty) return const SizedBox.shrink();
    if (choices.length == 1) return Padding(padding: const EdgeInsets.only(bottom: 12), child: Text('$label: ${choices.single.label}'));
    return Padding(padding: const EdgeInsets.only(bottom: 16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Text('This local time occurs twice. Choose the intended UTC offset.'),
      InputDecorator(decoration: InputDecoration(labelText: '$label UTC offset'), child: DropdownButtonHideUnderline(child: DropdownButton<HistoryTimeChoice>(
        isExpanded: true, itemHeight: null, value: selected, hint: const Text('Choose an offset'),
        items: choices.map((value) => DropdownMenuItem(value: value, child: Text(value.label))).toList(),
        onChanged: _resolving ? null : select,
      ))),
    ]));
  }
  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.initial == null ? 'Covered time' : 'Edit covered time'),
    content: SizedBox(width: 500, child: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const Text('Enter only the work already billed on the original invoice. This reserves billing coverage; it does not create or change a work session.'),
      const SizedBox(height: 20),
      _field(_start, 'Covered start'), _field(_end, 'Covered end'),
      TextField(controller: _zone, readOnly: _resolving, onChanged: _changed, decoration: const InputDecoration(labelText: 'IANA timezone', hintText: 'America/New_York')),
      const SizedBox(height: 16),
      OutlinedButton(onPressed: _resolving ? null : _resolve, child: Text(_resolving ? 'Resolving times…' : 'Review times and UTC offsets')),
      if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
      _choices('Start', _starts, _selectedStart, (value) => setState(() => _selectedStart = value)),
      _choices('End', _ends, _selectedEnd, (value) => setState(() => _selectedEnd = value)),
      if (_rangeError != null) Text(_rangeError!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
    ]))),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
      FilledButton(onPressed: _resolving || _error != null || _selectedStart == null || _selectedEnd == null || _rangeError != null ? null : () => Navigator.pop(context,
        LegacyCoveredTime(start: _selectedStart!.instant, end: _selectedEnd!.instant, timezone: _zone.text.trim(),
          startLabel: _selectedStart!.label, endLabel: _selectedEnd!.label, localStart: _start.text.trim(), localEnd: _end.text.trim())), child: const Text('Use covered time')),
    ],
  );
}
