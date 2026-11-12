import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'invoice_screen.dart';
import 'legacy_covered_time_dialog.dart';
import 'legacy_invoice_api.dart';

class LegacyInvoiceScreen extends ConsumerWidget {
  const LegacyInvoiceScreen({super.key, required this.invoiceId});
  final String invoiceId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final result = ref.watch(legacyInvoiceContextProvider(invoiceId));
    return result.when(
      skipLoadingOnReload: true,
      skipError: true,
      loading: () => Scaffold(
        appBar: AppBar(title: const Text('Review billed work')),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Scaffold(
        appBar: AppBar(title: const Text('Review billed work')),
        body: Center(
          child: FilledButton(
            onPressed: () =>
                ref.invalidate(legacyInvoiceContextProvider(invoiceId)),
            child: const Text('Retry coverage review'),
          ),
        ),
      ),
      data: (data) => _LegacyReviewForm(
        key: ValueKey(invoiceId),
        data: data,
        refreshFailed: result.hasError,
      ),
    );
  }
}

class _LegacyReviewForm extends ConsumerStatefulWidget {
  const _LegacyReviewForm({
    super.key,
    required this.data,
    required this.refreshFailed,
  });
  final LegacyInvoiceContext data;
  final bool refreshFailed;
  @override
  ConsumerState<_LegacyReviewForm> createState() => _LegacyReviewFormState();
}

class _LegacyReviewFormState extends ConsumerState<_LegacyReviewForm> {
  late final _note = TextEditingController(text: widget.data.note);
  final _search = TextEditingController();
  final _tasks = <String>{};
  final _times = <LegacyCoveredTime>[];
  bool _pending = false, _confirmed = false, _noSources = false;
  String? _noteError, _selectionError, _confirmationError;
  @override
  void dispose() {
    _note.dispose();
    _search.dispose();
    super.dispose();
  }

  bool get _editable =>
      widget.data.legacy && !widget.data.jobDeleted && !_pending;
  void _refresh() =>
      ref.invalidate(legacyInvoiceContextProvider(widget.data.invoiceId));
  void _open(String invoiceId) => Navigator.push(
    context,
    MaterialPageRoute<void>(
      builder: (_) => InvoiceScreen(invoiceId: invoiceId),
    ),
  );
  void _changed() {
    _confirmed = false;
    _selectionError = null;
    _confirmationError = null;
  }

  Future<void> _time([int? index]) async {
    if (!_editable || _noSources) return;
    final value = await showDialog<LegacyCoveredTime>(
      context: context,
      builder: (_) => LegacyCoveredTimeDialog(
        timezone: widget.data.timezone,
        initial: index == null ? null : _times[index],
        otherTimes: [
          for (var i = 0; i < _times.length; i++)
            if (i != index) _times[i],
        ],
        reservedTimes: widget.data.reservedTimes,
      ),
    );
    if (value == null || !mounted) return;
    setState(() {
      if (index == null) {
        _times.add(value);
      } else {
        _times[index] = value;
      }
      _changed();
    });
  }

  LegacyInvoiceDraft _draft() {
    final data = widget.data;
    final note = _note.text.trim();
    final available = data.tasks
        .where((value) => value.invoiceId == null)
        .map((value) => value.id)
        .toSet();
    final missing = _tasks.difference(available);
    setState(() {
      _noteError = note.length < 10 || note.length > 4000
          ? 'Add 10 to 4000 characters explaining this review.'
          : null;
      _confirmationError = _confirmed
          ? null
          : 'Confirm that you checked the original invoice.';
      _selectionError = missing.isNotEmpty
          ? 'Selected work changed or is already reserved. Clear the selection, refresh and review it again.'
          : (_tasks.isEmpty && _times.isEmpty) != _noSources
          ? 'Select covered tasks/time or explicitly acknowledge that no source records can be linked.'
          : null;
    });
    if (_noteError != null ||
        _confirmationError != null ||
        _selectionError != null) {
      throw const FormatException(
        'Correct the highlighted coverage review fields.',
      );
    }
    final tasks = _tasks.toList()..sort();
    return LegacyInvoiceDraft(
      invoiceId: data.invoiceId,
      tasks: tasks,
      times: _times,
      note: note,
      confirmed: _confirmed,
      noSources: _noSources,
    );
  }

  Widget _heading(String text) => Padding(
    padding: const EdgeInsets.only(top: 28, bottom: 12),
    child: Text(text, style: Theme.of(context).textTheme.titleLarge),
  );
  @override
  Widget build(BuildContext context) {
    final data = widget.data;
    final editing = data.legacy || _pending;
    final query = _search.text.trim().toLowerCase();
    final tasks = data.tasks.where(
      (value) => value.title.toLowerCase().contains(query),
    );
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Review billed work'),
          actions: [
            IconButton(
              tooltip: 'Refresh source records',
              onPressed: _pending ? null : _refresh,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: ListView(
                padding: const EdgeInsets.all(24),
                children: [
                  if (widget.refreshFailed) ...[
                    const Text(
                      'Could not refresh source records. Your review and current operation are kept.',
                    ),
                    TextButton(
                      onPressed: _pending ? null : _refresh,
                      child: const Text('Retry source records'),
                    ),
                  ],
                  Text(
                    data.number,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 8),
                  Text(data.jobName),
                  Text('${data.currency} ${data.total} · ${data.period}'),
                  const SizedBox(height: 16),
                  const Text(
                    'Select the work this invoice already billed. Its PDF, total and number stay unchanged. This review does not record a payment.',
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    onPressed: _pending ? null : () => _open(data.invoiceId),
                    icon: const Icon(Icons.picture_as_pdf_outlined),
                    label: const Text('View original invoice'),
                  ),
                  if (data.jobDeleted)
                    const Padding(
                      padding: EdgeInsets.only(top: 16),
                      child: Text(
                        'This job was deleted. Its financial records remain available; source review cannot modify deleted work.',
                      ),
                    ),
                  if (!data.legacy)
                    Padding(
                      padding: const EdgeInsets.only(top: 16),
                      child: Text(
                        _pending
                            ? 'Coverage is saved. Retry the same operation to confirm its outcome.'
                            : 'Coverage reviewed. ${data.note}',
                      ),
                    ),
                  if (editing) ...[
                    _heading('Covered work'),
                    Text(
                      '${_tasks.length} tasks · ${_times.length} time ranges selected',
                    ),
                    const Text(
                      'Nothing is selected automatically. Linking a task keeps its current completion and price.',
                    ),
                    if (_selectionError != null)
                      Text(
                        _selectionError!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    if (_tasks.isNotEmpty || _times.isNotEmpty)
                      TextButton(
                        onPressed: _editable
                            ? () => setState(() {
                                _tasks.clear();
                                _times.clear();
                                _changed();
                              })
                            : null,
                        child: const Text('Clear selected work'),
                      ),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      value: _noSources,
                      title: const Text('No source records can be linked'),
                      subtitle: const Text(
                        'Explain missing records or existing reservations in the review note.',
                      ),
                      onChanged: _editable && _tasks.isEmpty && _times.isEmpty
                          ? (value) => setState(() {
                              _noSources = value ?? false;
                              _changed();
                            })
                          : null,
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: _search,
                      readOnly: !_editable,
                      onChanged: (_) => setState(() {}),
                      decoration: const InputDecoration(
                        labelText: 'Search this job’s tasks',
                        prefixIcon: Icon(Icons.search),
                      ),
                    ),
                    const SizedBox(height: 8),
                    if (data.tasks.isEmpty)
                      const Text(
                        'No task records remain for this job. Add covered time if the original invoice billed hours, or explicitly review missing source records.',
                      ),
                    if (data.tasks.isNotEmpty && tasks.isEmpty)
                      const Text(
                        'No tasks match this search. Other selections are kept.',
                      ),
                    for (final task in tasks)
                      Column(
                        children: [
                          CheckboxListTile(
                            contentPadding: EdgeInsets.zero,
                            value: _tasks.contains(task.id),
                            title: Text(task.title),
                            subtitle: Text(
                              task.number != null
                                  ? 'Already reserved · ${task.number}'
                                  : task.completedLabel == null
                                  ? 'Not currently completed'
                                  : 'Completed ${task.completedLabel}',
                            ),
                            onChanged:
                                _editable &&
                                    !_noSources &&
                                    task.invoiceId == null
                                ? (value) => setState(() {
                                    if (value == true) {
                                      _tasks.add(task.id);
                                    } else {
                                      _tasks.remove(task.id);
                                    }
                                    _changed();
                                  })
                                : null,
                          ),
                          if (task.invoiceId != null)
                            Align(
                              alignment: Alignment.centerLeft,
                              child: TextButton(
                                onPressed: _pending
                                    ? null
                                    : () => _open(task.invoiceId!),
                                child: Text('Open ${task.number}'),
                              ),
                            ),
                        ],
                      ),
                    _heading('Covered time'),
                    for (var i = 0; i < _times.length; i++)
                      Card(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '${_times[i].startLabel} — ${_times[i].endLabel}',
                              ),
                              Text(_times[i].timezone),
                              Wrap(
                                spacing: 12,
                                children: [
                                  TextButton(
                                    onPressed: _editable
                                        ? () => _time(i)
                                        : null,
                                    child: const Text('Edit covered time'),
                                  ),
                                  TextButton(
                                    onPressed: _editable
                                        ? () => setState(() {
                                            _times.removeAt(i);
                                            _changed();
                                          })
                                        : null,
                                    child: const Text('Remove range'),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                    OutlinedButton.icon(
                      onPressed: _editable && !_noSources
                          ? () => _time()
                          : null,
                      icon: const Icon(Icons.add),
                      label: const Text('Add covered time'),
                    ),
                    _heading('Review note'),
                    Semantics(
                      identifier: 'legacy-invoice-review-note',
                      child: TextField(
                        controller: _note,
                        readOnly: !_editable,
                        maxLength: 4000,
                        minLines: 3,
                        maxLines: 6,
                        decoration: InputDecoration(
                          labelText: 'Why this work belongs to the invoice',
                          helperText: 'Private audit note; never added to the customer PDF.',
                          errorText: _noteError,
                          errorMaxLines: 3,
                        ),
                        onChanged: (_) {
                          if (_noteError != null) {
                            setState(() => _noteError = null);
                          }
                        },
                      ),
                    ),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      value: _confirmed,
                      title: const Text(
                        'I checked this against the original invoice',
                      ),
                      subtitle: const Text(
                        'I verified the selected work or explained missing source records in my note.',
                      ),
                      onChanged: _editable
                          ? (value) {
                              FocusScope.of(context).unfocus();
                              setState(() {
                                _confirmed = value ?? false;
                                _confirmationError = null;
                              });
                            }
                          : null,
                    ),
                    if (_confirmationError != null)
                      Text(
                        _confirmationError!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                  ],
                  if (data.reservedTimes.isNotEmpty) ...[
                    _heading('Existing time reservations'),
                    for (final value in data.reservedTimes)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(value.number),
                        subtitle: Text(
                          '${value.time.startLabel} — ${value.time.endLabel}\n${value.time.timezone}',
                        ),
                        trailing: const Icon(Icons.open_in_new),
                        onTap: _pending ? null : () => _open(value.invoiceId),
                      ),
                  ],
                ],
              ),
            ),
          ),
        ),
        bottomNavigationBar: SafeArea(
          top: false,
          child: Center(
            heightFactor: 1,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 12, 24, 16),
                child: editing
                    ? BusinessCommand(
                        key: const ValueKey('save-legacy-coverage'),
                        label: 'Save coverage review',
                        enabled: data.legacy && !data.jobDeleted,
                        onPendingChanged: (value) =>
                            setState(() => _pending = value),
                        create: () {
                          final draft = _draft(),
                              id = newBusinessId(),
                              version = data.version;
                          final api = ref.read(legacyInvoiceApiProvider);
                          return () => api.reconcile(id, version, draft);
                        },
                        onCommitted: () => Navigator.pop(context, true),
                      )
                    : FilledButton(
                        onPressed: () => Navigator.pop(context),
                        child: const Text('Done'),
                      ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
