import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_providers.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'report_api.dart';
import 'settings_api.dart';

const actualCostCategories = <String, String>{
  'MATERIALS': 'Materials',
  'SUBCONTRACT_LABOR': 'Subcontract labor',
  'OTHER_DIRECT': 'Other direct cost',
  'OVERHEAD': 'Overhead',
};

String _costDateLabel(ActualCostEntry entry) {
  final local = DateTime.fromMillisecondsSinceEpoch(
    entry.occurred,
    isUtc: true,
  ).add(Duration(seconds: entry.offsetSeconds));
  final date =
      '${local.year.toString().padLeft(4, '0')}-${local.month.toString().padLeft(2, '0')}-${local.day.toString().padLeft(2, '0')}';
  final time =
      '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
  return '$date $time · ${entry.timezone}';
}

class ActualCostScreen extends ConsumerStatefulWidget {
  const ActualCostScreen({super.key});
  @override
  ConsumerState<ActualCostScreen> createState() => _ActualCostScreenState();
}

class _ActualCostScreenState extends ConsumerState<ActualCostScreen>
    with WidgetsBindingObserver {
  List<ActualCostEntry> _entries = [];
  List<ReportJob> _jobs = [];
  ActualCostCursor? _next;
  int _version = 0;
  bool _loading = true, _loadingMore = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final result = await Future.wait<Object?>([
        ref.read(businessApiProvider).actualCosts(),
        ref.read(reportApiProvider).context(),
      ]);
      if (!mounted) return;
      final page = result[0] as ActualCostsSnapshot;
      setState(() {
        _entries = page.entries;
        _next = page.next;
        _version = page.version;
        _jobs = (result[1] as ReportContext).jobs;
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'Actual costs could not be loaded.';
        });
      }
    }
  }

  Future<void> _loadMore() async {
    final cursor = _next;
    if (cursor == null || _loadingMore) return;
    setState(() => _loadingMore = true);
    try {
      final page = await ref
          .read(businessApiProvider)
          .actualCosts(before: cursor);
      if (!mounted) return;
      setState(() {
        _entries = [..._entries, ...page.entries];
        _next = page.next;
        _version = page.version;
        _loadingMore = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingMore = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('More costs could not be loaded. Try again.'),
          ),
        );
      }
    }
  }

  Future<void> _edit([ActualCostEntry? entry]) async {
    final saved = await Navigator.push<bool>(
      context,
      MaterialPageRoute<bool>(
        builder: (_) =>
            ActualCostEditor(version: _version, jobs: _jobs, initial: entry),
      ),
    );
    if (saved == true && mounted) await _load();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Actual costs'),
      actions: [
        IconButton(
          tooltip: 'Refresh costs',
          onPressed: _load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    floatingActionButton: FloatingActionButton.extended(
      onPressed: _loading ? null : () => _edit(),
      icon: const Icon(Icons.add),
      label: const Text('Add cost'),
    ),
    body: _loading
        ? const Center(child: CircularProgressIndicator())
        : _error != null
        ? Center(
            child: TextButton(onPressed: _load, child: Text('$_error Retry')),
          )
        : _entries.isEmpty
        ? const Center(
            child: Padding(
              padding: EdgeInsets.all(32),
              child: Text(
                'No actual costs yet. Record receipts, subcontract labor and overhead here. Task supplies labels help you classify a cost, but never create one automatically.',
              ),
            ),
          )
        : ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 96),
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(4, 0, 4, 12),
                child: Text(
                  'Costs stay separate from invoice totals and are kept in their recorded currency.',
                ),
              ),
              for (final entry in _entries)
                Card(
                  child: ListTile(
                    title: Text(
                      entry.vendor.isEmpty
                          ? actualCostCategories[entry.category] ??
                                entry.category
                          : entry.vendor,
                    ),
                    subtitle: Text(
                      [
                        '${actualCostCategories[entry.category] ?? entry.category} · ${_costDateLabel(entry)}',
                        if (entry.jobName.isNotEmpty)
                          entry.taskName.isEmpty
                              ? entry.jobName
                              : '${entry.jobName} · ${entry.taskName}',
                        if (entry.reference.isNotEmpty)
                          'Reference ${entry.reference}',
                        if (entry.note.isNotEmpty) entry.note,
                      ].join('\n'),
                    ),
                    isThreeLine: true,
                    trailing: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 132),
                      child: Text(
                        '${entry.currency} ${money(entry.amountMinor, entry.digits)}',
                        textAlign: TextAlign.end,
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                    ),
                    onTap: () => _edit(entry),
                  ),
                ),
              if (_next != null)
                Center(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: OutlinedButton(
                      onPressed: _loadingMore ? null : _loadMore,
                      child: Text(
                        _loadingMore ? 'Loading…' : 'Load more costs',
                      ),
                    ),
                  ),
                ),
            ],
          ),
  );
}

class ActualCostEditor extends ConsumerStatefulWidget {
  const ActualCostEditor({
    super.key,
    required this.version,
    required this.jobs,
    this.initial,
  });
  final int version;
  final List<ReportJob> jobs;
  final ActualCostEntry? initial;
  @override
  ConsumerState<ActualCostEditor> createState() => _ActualCostEditorState();
}

class _ActualCostEditorState extends ConsumerState<ActualCostEditor> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _currency = TextEditingController();
  final _vendor = TextEditingController();
  final _reference = TextEditingController();
  final _note = TextEditingController();
  late DateTime _occurred;
  String _category = 'MATERIALS';
  String? _jobId, _taskId, _timezone;
  int _digits = 2;
  late int _version;
  bool? _currencyValid;
  bool _loading = true, _deleting = false;

  @override
  void initState() {
    super.initState();
    final old = widget.initial;
    _version = widget.version;
    _occurred = old == null
        ? DateTime.now()
        : DateTime.fromMillisecondsSinceEpoch(old.occurred).toLocal();
    _jobId = old?.jobId;
    _taskId = old?.taskId;
    _category = old?.category ?? 'MATERIALS';
    _currency.text = old?.currency ?? '';
    _digits = old?.digits ?? 2;
    _currencyValid = old == null ? null : true;
    if (old != null) _amount.text = money(old.amountMinor, old.digits);
    _vendor.text = old?.vendor ?? '';
    _reference.text = old?.reference ?? '';
    _note.text = old?.note ?? '';
    _timezone = old?.timezone;
    _loadDefaults();
  }

  @override
  void dispose() {
    _amount.dispose();
    _currency.dispose();
    _vendor.dispose();
    _reference.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _loadDefaults() async {
    try {
      final result = await Future.wait<Object?>([
        ref.read(businessSettingsApiProvider).read(),
        ref.read(reportApiProvider).context(),
      ]);
      if (!mounted) return;
      final settings = (result[0] as BusinessSettings).settings;
      final report = result[1] as ReportContext;
      if (widget.initial == null) {
        _currency.text = settings.currency;
        _digits = settings.digits;
        _currencyValid = true;
      }
      _timezone ??= report.timezone;
      setState(() => _loading = false);
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _chooseDateTime() async {
    final date = await showDatePicker(
      context: context,
      initialDate: _occurred,
      firstDate: DateTime(2000),
      lastDate: DateTime.now(),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(_occurred),
    );
    if (time == null || !mounted) return;
    setState(
      () => _occurred = DateTime(
        date.year,
        date.month,
        date.day,
        time.hour,
        time.minute,
      ),
    );
  }

  Future<void> _setCurrencyDigits(String value) async {
    final currency = value.trim().toUpperCase();
    if (!RegExp(r'^[A-Z]{3}$').hasMatch(currency)) return;
    try {
      final digits = await ref
          .read(businessApiProvider)
          .currencyDigits(currency);
      if (mounted && _currency.text.trim().toUpperCase() == currency) {
        setState(() {
          _digits = digits;
          _currencyValid = true;
        });
      }
    } catch (_) {
      if (mounted && _currency.text.trim().toUpperCase() == currency) {
        setState(() => _currencyValid = false);
      }
    }
  }

  ActualCostEntry _draft() {
    if (!(_form.currentState?.validate() ?? false)) {
      throw const FormatException('Fix the highlighted fields before saving.');
    }
    final currency = _currency.text.trim().toUpperCase();
    if (_currencyValid != true) {
      throw const FormatException(
        'Choose a supported currency code and wait for it to validate.',
      );
    }
    final digits = _digits;
    final minor = parseMoney(_amount.text, digits);
    if (minor == null || BigInt.parse(minor) <= BigInt.zero) {
      throw const FormatException('Enter a cost greater than zero.');
    }
    if (_occurred.isAfter(DateTime.now())) {
      throw const FormatException(
        'Choose a cost date and time that is not in the future.',
      );
    }
    final timezone = _timezone;
    if (timezone == null) {
      throw const FormatException(
        'The local timezone could not be read. Retry after refreshing the screen.',
      );
    }
    return ActualCostEntry(
      id: widget.initial?.id ?? newBusinessId(),
      jobId: _jobId,
      taskId: _taskId,
      amountMinor: minor,
      currency: currency,
      digits: digits,
      category: _category,
      occurred: _occurred.toUtc().millisecondsSinceEpoch,
      timezone: timezone,
      vendor: _vendor.text.trim(),
      reference: _reference.text.trim(),
      note: _note.text.trim(),
    );
  }

  Future<void> _confirmDelete() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: const Text('Delete this cost?'),
        content: const Text(
          'This removes the cost from active totals. Its audit history is retained.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialog, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialog, true),
            child: const Text('Delete cost'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) setState(() => _deleting = true);
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(timerVersionsProvider, (_, next) {
      next.whenData((version) {
        if (mounted) setState(() => _version = version);
      });
    });
    final old = widget.initial;
    final selectedJob = _jobId;
    return Scaffold(
      appBar: AppBar(
        title: Text(old == null ? 'Add actual cost' : 'Edit actual cost'),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Form(
              key: _form,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
                children: [
                  TextFormField(
                    controller: _amount,
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: const InputDecoration(
                      labelText: 'Amount',
                      prefixIcon: Icon(Icons.payments_outlined),
                    ),
                    validator: (v) {
                      try {
                        final value = parseMoney(v ?? '', _digits);
                        if (value == null || BigInt.parse(value) <= BigInt.zero) {
                          return 'Enter an amount greater than zero.';
                        }
                        return null;
                      } on FormatException catch (e) {
                        return e.message;
                      }
                    },
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _currency,
                    textCapitalization: TextCapitalization.characters,
                    maxLength: 3,
                    onChanged: (value) {
                      setState(() => _currencyValid = null);
                      _setCurrencyDigits(value);
                    },
                    decoration: const InputDecoration(
                      labelText: 'Currency code',
                      hintText: 'USD',
                    ),
                    validator: (v) {
                      if (!RegExp(r'^[A-Z]{3}$')
                          .hasMatch((v ?? '').trim().toUpperCase())) {
                        return 'Enter a supported three-letter currency code.';
                      }
                      if (_currencyValid == false) {
                        return 'This currency code is not supported.';
                      }
                      if (_currencyValid != true) {
                        return 'Wait for the currency code to be checked.';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<String>(
                    initialValue: _category,
                    decoration: const InputDecoration(
                      labelText: 'Cost category',
                    ),
                    items: [
                      for (final item in actualCostCategories.entries)
                        DropdownMenuItem(
                          value: item.key,
                          child: Text(item.value),
                        ),
                    ],
                    onChanged: (value) {
                      if (value != null) setState(() => _category = value);
                    },
                  ),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<String?>(
                    initialValue: selectedJob,
                    decoration: const InputDecoration(
                      labelText: 'Job (optional)',
                    ),
                    items: [
                      const DropdownMenuItem<String?>(
                        value: null,
                        child: Text('General / unlinked cost'),
                      ),
                      for (final job in widget.jobs)
                        DropdownMenuItem<String?>(
                          value: job.id,
                          child: Text(
                            '${job.name}${job.archived ? ' · archived' : ''}',
                          ),
                        ),
                      if (selectedJob != null &&
                          !widget.jobs.any((job) => job.id == selectedJob))
                        DropdownMenuItem<String?>(
                          value: selectedJob,
                          child: Text(
                            '${old?.jobName ?? 'Previously linked job'} · unavailable',
                          ),
                        ),
                    ],
                    onChanged: (value) => setState(() {
                      if (_jobId != value) _taskId = null;
                      _jobId = value;
                    }),
                  ),
                  if (selectedJob != null) ...[
                    const SizedBox(height: 8),
                    ref
                        .watch(boardProvider(selectedJob))
                        .when(
                          loading: () => const LinearProgressIndicator(),
                          error: (_, _) => const Text(
                            'Tasks could not be loaded. You can leave the cost linked to its job only.',
                          ),
                          data: (board) => DropdownButtonFormField<String?>(
                            initialValue: _taskId,
                            decoration: const InputDecoration(
                              labelText: 'Task (optional)',
                            ),
                            items: [
                              const DropdownMenuItem<String?>(
                                value: null,
                                child: Text('No task selected'),
                              ),
                              for (final task in board.cards)
                                DropdownMenuItem<String?>(
                                  value: task.id,
                                  child: Text(task.title),
                                ),
                              if (_taskId != null &&
                                  !board.cards.any(
                                    (task) => task.id == _taskId,
                                  ))
                                DropdownMenuItem<String?>(
                                  value: _taskId,
                                  child: Text(
                                    '${old?.taskName ?? 'Previously linked task'} · unavailable',
                                  ),
                                ),
                            ],
                            onChanged: (value) =>
                                setState(() => _taskId = value),
                          ),
                        ),
                  ],
                  const SizedBox(height: 12),
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.event_outlined),
                      title: const Text('Cost date and time'),
                      subtitle: Text(
                        '${_occurred.year.toString().padLeft(4, '0')}-${_occurred.month.toString().padLeft(2, '0')}-${_occurred.day.toString().padLeft(2, '0')} · ${TimeOfDay.fromDateTime(_occurred).format(context)}${_timezone == null ? '' : ' · $_timezone'}',
                      ),
                      trailing: const Icon(Icons.edit_calendar_outlined),
                      onTap: _chooseDateTime,
                    ),
                  ),
                  TextFormField(
                    controller: _vendor,
                    decoration: const InputDecoration(
                      labelText: 'Vendor (optional)',
                    ),
                  ),
                  TextFormField(
                    controller: _reference,
                    decoration: const InputDecoration(
                      labelText: 'Receipt or reference (optional)',
                    ),
                  ),
                  TextFormField(
                    controller: _note,
                    minLines: 2,
                    maxLines: 4,
                    decoration: const InputDecoration(
                      labelText: 'Notes (optional)',
                    ),
                  ),
                  const SizedBox(height: 24),
                  const Text(
                    'This records a real cost. Supplies task labels are classification only and do not add ledger entries automatically.',
                  ),
                ],
              ),
            ),
      bottomNavigationBar: _loading
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (_deleting && old != null)
                      BusinessCommand(
                        label: 'Delete cost',
                        create: () {
                          final id = newBusinessId();
                          return () => ref
                              .read(businessApiProvider)
                              .removeActualCost(id, _version, old.id);
                        },
                        onCommitted: () => Navigator.pop(context, true),
                      )
                    else
                      BusinessCommand(
                        label: old == null ? 'Save cost' : 'Save changes',
                        create: () {
                          final entry = _draft();
                          final id = newBusinessId();
                          return () => ref
                              .read(businessApiProvider)
                              .saveActualCost(id, _version, entry);
                        },
                        onCommitted: () => Navigator.pop(context, true),
                      ),
                    if (old != null && !_deleting)
                      TextButton.icon(
                        onPressed: _confirmDelete,
                        icon: const Icon(
                          Icons.delete_outline,
                          color: Colors.red,
                        ),
                        label: const Text(
                          'Delete cost',
                          style: TextStyle(color: Colors.red),
                        ),
                      ),
                  ],
                ),
              ),
            ),
    );
  }
}
