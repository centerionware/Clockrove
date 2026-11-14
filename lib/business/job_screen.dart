import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/history_screen.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'job_settings_screen.dart';
import 'report_screen.dart';
import 'task_conversation_screen.dart';

class JobScreen extends ConsumerWidget {
  const JobScreen({super.key, required this.jobId, required this.jobName});
  final String jobId, jobName;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final data = ref.watch(boardProvider(jobId));
    return Scaffold(
      appBar: AppBar(
        title: Text(
          data.value?.name ?? jobName,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        actions: [
          IconButton(
            tooltip: 'Work history',
            icon: const Icon(Icons.history),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => HistoryScreen(
                  jobId: jobId,
                  jobName: data.value?.name ?? jobName,
                ),
              ),
            ),
          ),
          IconButton(
            tooltip: 'Job report',
            icon: const Icon(Icons.assessment_outlined),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => ReportScreen(jobId: jobId),
              ),
            ),
          ),
          IconButton(
            tooltip: 'Job settings',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => JobSettingsScreen(jobId: jobId),
              ),
            ),
          ),
        ],
      ),
      body: data.when(
        skipLoadingOnReload: true,
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: FilledButton(
            onPressed: () => ref.invalidate(boardProvider(jobId)),
            child: const Text('Retry job'),
          ),
        ),
        data: (board) => _Board(board: board),
      ),
    );
  }
}

class _Board extends ConsumerWidget {
  const _Board({required this.board});
  final JobBoard board;
  Future<void> _editColumn(BuildContext context, [BoardColumn? column]) =>
      Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => _ColumnEditor(jobId: board.jobId, column: column),
        ),
      );
  Future<void> _task(BuildContext context, String columnId, [TaskCard? card]) =>
      Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) =>
              _TaskEditor(jobId: board.jobId, columnId: columnId, card: card),
        ),
      );
  Future<void> _conversation(BuildContext context, TaskCard card) =>
      Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => TaskConversationScreen(
            jobId: board.jobId,
            taskId: card.id,
            taskTitle: card.title,
          ),
        ),
      );
  Future<void> _move(
    BuildContext context,
    WidgetRef ref,
    TaskCard card,
    String destination,
    int position,
  ) async {
    final api = ref.read(businessApiProvider);
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialog) => Consumer(
        builder: (dialog, dialogRef, _) {
          final latest =
              dialogRef.watch(boardProvider(board.jobId)).value ?? board;
          final moving = latest.cards.where((c) => c.id == card.id).firstOrNull;
          final target = latest.columns
              .where((c) => c.id == destination)
              .firstOrNull;
          final available =
              moving != null && target != null && !latest.archived;
          final count = latest
              .inColumn(destination)
              .where((c) => c.id != card.id)
              .length;
          return AlertDialog(
            title: const Text('Move task'),
            content: Text(
              moving != null && target != null && !latest.archived
                  ? 'Move “${moving.title}” to ${target.name}?'
                  : 'The task or destination is no longer available.',
            ),
            actions: [
              BusinessCommand(
                label: 'Move task',
                enabled: available,
                create: () {
                  final id = newBusinessId();
                  return () => api.moveCard(
                    id,
                    latest,
                    moving!,
                    destination,
                    position.clamp(0, count),
                  );
                },
                onCommitted: () => Navigator.pop(dialog),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _taskTile(BuildContext context, WidgetRef ref, TaskCard card) {
    final inColumn = board.inColumn(card.columnId);
    final index = inColumn.indexOf(card);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        onTap: board.archived
            ? null
            : () => _task(context, card.columnId, card),
        title: Text(
          card.title,
          style: const TextStyle(fontWeight: FontWeight.w600),
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (card.description.isNotEmpty)
                Text(
                  card.description,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              if (card.dueDate != null) Text('Due ${card.dueDate}'),
              Text(
                !card.billable
                    ? 'Nonbillable'
                    : card.priceMinor == null
                    ? 'Price not set'
                    : '${board.currency} ${money(card.amountMinor, board.digits)}',
              ),
              if (card.priceMinor != null)
                Text(
                  '${taskQuantity(card.quantityUnits)} × ${card.currency} ${money(card.priceMinor, board.digits)} each',
                ),
              if (card.chargeType == 'SUPPLIES') const Text('Supplies'),
              if (card.chargeType == 'UNCLASSIFIED')
                const Text('Needs labor or supplies classification'),
              if (card.completedMillis != null) const Text('Completed'),
            ],
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: 'Task notes and photos',
              icon: const Icon(Icons.forum_outlined),
              onPressed: () => _conversation(context, card),
            ),
            if (!board.archived)
              PopupMenuButton<String>(
                tooltip: 'Task actions',
                onSelected: (value) {
                  if (value == 'edit') {
                    _task(context, card.columnId, card);
                  } else if (value == 'up') {
                    _move(context, ref, card, card.columnId, index - 1);
                  } else if (value == 'down') {
                    _move(context, ref, card, card.columnId, index + 1);
                  } else {
                    _move(
                      context,
                      ref,
                      card,
                      value,
                      board.inColumn(value).length,
                    );
                  }
                },
                itemBuilder: (_) => [
                  const PopupMenuItem(value: 'edit', child: Text('Edit task')),
                  if (index > 0)
                    const PopupMenuItem(value: 'up', child: Text('Move up')),
                  if (index + 1 < inColumn.length)
                    const PopupMenuItem(
                      value: 'down',
                      child: Text('Move down'),
                    ),
                  for (final column in board.columns.where(
                    (c) => c.id != card.columnId,
                  ))
                    PopupMenuItem(
                      value: column.id,
                      child: Text('Move to ${column.name}'),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Wrap(
          alignment: WrapAlignment.spaceBetween,
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: 12,
          runSpacing: 8,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Task board',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                Text(
                  '${board.cards.where((c) => c.completedMillis != null).length} of ${board.cards.length} tasks complete',
                ),
              ],
            ),
            if (!board.archived)
              OutlinedButton.icon(
                onPressed: () => _editColumn(context),
                icon: const Icon(Icons.add),
                label: const Text('Column'),
              ),
          ],
        ),
      ),
      if (board.archived)
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 20),
          child: Text('Archived job · board is read-only'),
        ),
      Expanded(
        // This is a full-page board. Build tooltip-bearing cards during build,
        // rather than reparenting their overlay portals from a layout callback.
        child: Builder(
          builder: (context) {
            final windowWidth = MediaQuery.sizeOf(context).width;
            if (windowWidth <= 40) {
              return const SizedBox.shrink();
            }
            return SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final column in board.columns)
                    Padding(
                      padding: const EdgeInsets.only(right: 16),
                      child: SizedBox(
                        width: windowWidth < 400 ? windowWidth - 40 : 320,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: Theme.of(context)
                                .colorScheme
                                .surfaceContainerLow,
                            borderRadius: BorderRadius.circular(20),
                          ),
                          child: Column(
                            children: [
                              ListTile(
                                title: Text(
                                  column.name,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                                subtitle: Text(
                                  column.completed
                                      ? 'Completes tasks'
                                      : '${board.inColumn(column.id).length} tasks',
                                ),
                                trailing: board.archived
                                    ? null
                                    : IconButton(
                                        tooltip: 'Edit column',
                                        icon: const Icon(Icons.more_horiz),
                                        onPressed: () =>
                                            _editColumn(context, column),
                                      ),
                              ),
                              Expanded(
                                child: ListView(
                                  primary: false,
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 12,
                                  ),
                                  children: [
                                    for (final card in board.inColumn(
                                      column.id,
                                    ))
                                      DragTarget<TaskCard>(
                                        onWillAcceptWithDetails: (d) =>
                                            !board.archived &&
                                            d.data.id != card.id,
                                        onAcceptWithDetails: (d) => _move(
                                          context,
                                          ref,
                                          d.data,
                                          column.id,
                                          card.position.clamp(
                                            0,
                                            board
                                                .inColumn(column.id)
                                                .where((c) => c.id != d.data.id)
                                                .length,
                                          ),
                                        ),
                                        builder: (_, candidates, _) => DecoratedBox(
                                          decoration: BoxDecoration(
                                            border: candidates.isEmpty
                                                ? null
                                                : Border(
                                                    top: BorderSide(
                                                      color: Theme.of(context)
                                                          .colorScheme
                                                          .primary,
                                                      width: 3,
                                                    ),
                                                  ),
                                          ),
                                          child: board.archived
                                              ? _taskTile(context, ref, card)
                                              : LongPressDraggable<TaskCard>(
                                                  data: card,
                                                  feedback: Material(
                                                    elevation: 8,
                                                    borderRadius:
                                                        BorderRadius.circular(
                                                          16,
                                                        ),
                                                    child: SizedBox(
                                                      width: 260,
                                                      child: Padding(
                                                        padding:
                                                            const EdgeInsets.all(
                                                              20,
                                                            ),
                                                        child: Text(card.title),
                                                      ),
                                                    ),
                                                  ),
                                                  childWhenDragging: Opacity(
                                                    opacity: .3,
                                                    child: _taskTile(
                                                      context,
                                                      ref,
                                                      card,
                                                    ),
                                                  ),
                                                  child: _taskTile(
                                                    context,
                                                    ref,
                                                    card,
                                                  ),
                                                ),
                                        ),
                                      ),
                                    if (!board.archived)
                                      DragTarget<TaskCard>(
                                        onWillAcceptWithDetails: (_) => true,
                                        onAcceptWithDetails: (d) => _move(
                                          context,
                                          ref,
                                          d.data,
                                          column.id,
                                          board
                                              .inColumn(column.id)
                                              .where((c) => c.id != d.data.id)
                                              .length,
                                        ),
                                        builder: (_, candidates, _) => Padding(
                                          padding: const EdgeInsets.symmetric(
                                            vertical: 8,
                                          ),
                                          child: Column(
                                            crossAxisAlignment:
                                                CrossAxisAlignment.stretch,
                                            children: [
                                              if (candidates.isNotEmpty)
                                                const Padding(
                                                  padding: EdgeInsets.all(12),
                                                  child: Text(
                                                    'Drop to move here',
                                                  ),
                                                ),
                                              TextButton.icon(
                                                onPressed: () =>
                                                    _task(context, column.id),
                                                icon: const Icon(Icons.add),
                                                label: const Text('Add task'),
                                              ),
                                              TextButton.icon(
                                                onPressed: () => Navigator.push(
                                                  context,
                                                  MaterialPageRoute<void>(
                                                    builder: (_) => _Templates(
                                                      jobId: board.jobId,
                                                      columnId: column.id,
                                                    ),
                                                  ),
                                                ),
                                                icon: const Icon(
                                                  Icons.copy_outlined,
                                                ),
                                                label: const Text(
                                                  'Reuse a task',
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            );
          },
        ),
      ),
    ],
  );
}

class _TaskEditor extends ConsumerStatefulWidget {
  const _TaskEditor({required this.jobId, required this.columnId, this.card});
  final String jobId, columnId;
  final TaskCard? card;
  @override
  ConsumerState<_TaskEditor> createState() => _TaskEditorState();
}

class _TaskEditorState extends ConsumerState<_TaskEditor> {
  final _form = GlobalKey<FormState>();
  late final _title = TextEditingController(text: widget.card?.title ?? '');
  late final _description = TextEditingController(
    text: widget.card?.description ?? '',
  );
  late final _price = TextEditingController(
    text: money(
      widget.card?.priceMinor,
      ref.read(boardProvider(widget.jobId)).requireValue.digits,
    ),
  );
  late final _quantity = TextEditingController(
    text: taskQuantity(widget.card?.quantityUnits ?? '10000'),
  );
  late final _id = widget.card?.id ?? newBusinessId();
  late bool _billable = widget.card?.billable ?? true;
  late String _chargeType = widget.card?.chargeType ?? 'LABOR';
  late String? _due = widget.card?.dueDate;
  @override
  void dispose() {
    _title.dispose();
    _description.dispose();
    _price.dispose();
    _quantity.dispose();
    super.dispose();
  }

  String _total(JobBoard board) {
    try {
      final total = taskAmount(
        parseMoney(_price.text, board.digits),
        parseTaskQuantity(_quantity.text),
      );
      return total == null
          ? 'Task total: unit price not set'
          : 'Task total: ${board.currency} ${money(total, board.digits)}';
    } on FormatException {
      return 'Task total: check unit price and quantity';
    }
  }

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: _due == null ? now : DateTime.parse(_due!),
      firstDate: DateTime(1900),
      lastDate: DateTime(9999),
    );
    if (date != null && mounted) {
      setState(
        () => _due =
            '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final board = ref.watch(boardProvider(widget.jobId)).value;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.card == null ? 'New task' : 'Edit task'),
      ),
      body: board == null
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Form(
                key: _form,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextFormField(
                      controller: _title,
                      decoration: const InputDecoration(
                        labelText: 'Task title',
                      ),
                      maxLength: 240,
                      validator: (v) => v == null || v.trim().isEmpty
                          ? 'Enter a task title'
                          : null,
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      controller: _description,
                      decoration: const InputDecoration(
                        labelText: 'Description',
                      ),
                      minLines: 3,
                      maxLines: 8,
                      maxLength: 20000,
                    ),
                    const SizedBox(height: 16),
                    Semantics(
                      identifier: 'task-unit-price',
                      child: TextFormField(
                        controller: _price,
                        onChanged: (_) => setState(() {}),
                        decoration: InputDecoration(
                          labelText: 'Unit price (${board.currency})',
                          helperText:
                              'Blank: price not set. 0: explicitly free.',
                        ),
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        validator: (v) {
                          try {
                            parseMoney(v ?? '', board.digits);
                            return null;
                          } on FormatException catch (e) {
                            return e.message;
                          }
                        },
                      ),
                    ),
                    const SizedBox(height: 16),
                    Semantics(
                      identifier: 'task-quantity',
                      child: TextFormField(
                        controller: _quantity,
                        decoration: const InputDecoration(
                          labelText: 'Quantity',
                          helperText: 'How many units? Defaults to 1.',
                        ),
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        onChanged: (_) => setState(() {}),
                        validator: (v) {
                          try {
                            parseTaskQuantity(v ?? '');
                            return null;
                          } on FormatException catch (e) {
                            return e.message;
                          }
                        },
                      ),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      _total(board),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Billable task'),
                      value: _billable,
                      onChanged: (v) => setState(() => _billable = v),
                    ),
                    if (_chargeType == 'UNCLASSIFIED') ...[
                      const Align(
                        alignment: Alignment.centerLeft,
                        child: Text(
                          'Choose how this existing task is classified for reports.',
                        ),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        children: [
                          ChoiceChip(
                            label: const Text('Labor'),
                            selected: _chargeType == 'LABOR',
                            onSelected: (_) =>
                                setState(() => _chargeType = 'LABOR'),
                          ),
                          ChoiceChip(
                            label: const Text('Supplies'),
                            selected: _chargeType == 'SUPPLIES',
                            onSelected: (_) =>
                                setState(() => _chargeType = 'SUPPLIES'),
                          ),
                        ],
                      ),
                    ] else
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Supplies'),
                        subtitle: const Text(
                          'Turn on for materials. Off means labor.',
                        ),
                        value: _chargeType == 'SUPPLIES',
                        onChanged: (value) => setState(
                          () => _chargeType = value ? 'SUPPLIES' : 'LABOR',
                        ),
                      ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.calendar_today_outlined),
                      title: Text(_due ?? 'No due date'),
                      onTap: _pickDate,
                      trailing: _due == null
                          ? null
                          : IconButton(
                              tooltip: 'Remove due date',
                              icon: const Icon(Icons.close),
                              onPressed: () => setState(() => _due = null),
                            ),
                    ),
                    const SizedBox(height: 24),
                    BusinessCommand(
                      label: 'Save task',
                      create: () {
                        if (!_form.currentState!.validate()) {
                          throw const FormatException(
                            'Check the highlighted fields.',
                          );
                        }
                        if (_chargeType == 'UNCLASSIFIED') {
                          throw const FormatException(
                            'Choose Labor or Supplies before saving.',
                          );
                        }
                        final command = newBusinessId();
                        final card = TaskCard(
                          id: _id,
                          columnId: widget.card?.columnId ?? widget.columnId,
                          title: _title.text.trim(),
                          description: _description.text,
                          position: widget.card?.position ?? 0,
                          billable: _billable,
                          currency: board.currency,
                          dueDate: _due,
                          priceMinor: parseMoney(_price.text, board.digits),
                          quantityUnits: parseTaskQuantity(_quantity.text),
                          chargeType: _chargeType,
                        );
                        return () => ref
                            .read(businessApiProvider)
                            .saveCard(command, board, card);
                      },
                      onCommitted: () => Navigator.pop(context),
                    ),
                    if (widget.card != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 24),
                        child: OutlinedButton.icon(
                          style: OutlinedButton.styleFrom(
                            foregroundColor: Theme.of(context)
                                .colorScheme
                                .error,
                          ),
                          onPressed: () => showDialog<void>(
                            context: context,
                            barrierDismissible: false,
                            builder: (dialog) => AlertDialog(
                              title: const Text('Delete task?'),
                              content: const Text(
                                'The reusable template is kept in your task library.',
                              ),
                              actions: [
                                BusinessCommand(
                                  label: 'Delete task',
                                  create: () {
                                    final id = newBusinessId();
                                    return () => ref
                                        .read(businessApiProvider)
                                        .removeCard(id, board, widget.card!);
                                  },
                                  onCommitted: () {
                                    Navigator.pop(dialog);
                                    Navigator.pop(context);
                                  },
                                ),
                              ],
                            ),
                          ),
                          icon: const Icon(Icons.delete_outline),
                          label: const Text('Delete task'),
                        ),
                      ),
                  ],
                ),
              ),
            ),
    );
  }
}

class _ColumnEditor extends ConsumerStatefulWidget {
  const _ColumnEditor({required this.jobId, this.column});
  final String jobId;
  final BoardColumn? column;
  @override
  ConsumerState<_ColumnEditor> createState() => _ColumnEditorState();
}

class _ColumnEditorState extends ConsumerState<_ColumnEditor> {
  late final _name = TextEditingController(text: widget.column?.name ?? '');
  late bool _completed = widget.column?.completed ?? false;
  String? _destination;
  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final board = ref.watch(boardProvider(widget.jobId)).value;
    final column = widget.column;
    return Scaffold(
      appBar: AppBar(
        title: Text(column == null ? 'New column' : 'Column settings'),
      ),
      body: board == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(24),
              children: [
                TextField(
                  controller: _name,
                  maxLength: 120,
                  decoration: const InputDecoration(labelText: 'Column name'),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Tasks in this column are completed'),
                  subtitle: const Text(
                    'Moving a task here records its completion date. Moving it out reopens it.',
                  ),
                  value: _completed,
                  onChanged: (v) => setState(() => _completed = v),
                ),
                const SizedBox(height: 16),
                BusinessCommand(
                  label: 'Save column',
                  create: () {
                    if (_name.text.trim().isEmpty) {
                      throw const FormatException('Enter a column name.');
                    }
                    final id = newBusinessId();
                    final columnId = column?.id ?? newBusinessId();
                    final name = _name.text.trim();
                    final completed = _completed;
                    return () => ref
                        .read(businessApiProvider)
                        .saveColumn(id, board, columnId, name, completed);
                  },
                  onCommitted: () => Navigator.pop(context),
                ),
                if (column != null) ...[
                  const SizedBox(height: 32),
                  Text(
                    'Column order',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  for (final offset in [-1, 1])
                    if (board.columns.indexWhere((c) => c.id == column.id) +
                                offset >=
                            0 &&
                        board.columns.indexWhere((c) => c.id == column.id) +
                                offset <
                            board.columns.length)
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: BusinessCommand(
                          label: offset < 0
                              ? 'Move column left'
                              : 'Move column right',
                          create: () {
                            final ids = board.columns.map((c) => c.id).toList();
                            final index = ids.indexOf(column.id);
                            ids.removeAt(index);
                            ids.insert(index + offset, column.id);
                            final id = newBusinessId();
                            return () => ref
                                .read(businessApiProvider)
                                .orderColumns(id, board, ids);
                          },
                          onCommitted: () => Navigator.pop(context),
                        ),
                      ),
                  if (board.columns.length > 1) ...[
                    const SizedBox(height: 32),
                    Text(
                      'Delete column',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                    if (board.inColumn(column.id).isNotEmpty)
                      DropdownButtonFormField<String>(
                        initialValue: _destination,
                        isExpanded: true,
                        decoration: const InputDecoration(
                          labelText: 'Move existing tasks to',
                        ),
                        items: [
                          for (final c in board.columns.where(
                            (c) => c.id != column.id,
                          ))
                            DropdownMenuItem(value: c.id, child: Text(c.name)),
                        ],
                        onChanged: (v) => setState(() => _destination = v),
                      ),
                    const SizedBox(height: 12),
                    BusinessCommand(
                      label: 'Delete column and move tasks',
                      enabled:
                          board.inColumn(column.id).isEmpty ||
                          _destination != null,
                      create: () {
                        final id = newBusinessId();
                        final destination = _destination;
                        return () => ref
                            .read(businessApiProvider)
                            .removeColumn(id, board, column.id, destination);
                      },
                      onCommitted: () => Navigator.pop(context),
                    ),
                  ],
                ],
              ],
            ),
    );
  }
}

class _Templates extends ConsumerStatefulWidget {
  const _Templates({required this.jobId, required this.columnId});
  final String jobId, columnId;
  @override
  ConsumerState<_Templates> createState() => _TemplatesState();
}

class _TemplatesState extends ConsumerState<_Templates> {
  late Future<List<TaskTemplate>> _data = ref
      .read(businessApiProvider)
      .templates('');
  final _search = TextEditingController();
  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Reusable tasks')),
    body: Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(20),
          child: TextField(
            controller: _search,
            decoration: InputDecoration(
              labelText: 'Search task library',
              suffixIcon: IconButton(
                tooltip: 'Search',
                icon: const Icon(Icons.search),
                onPressed: () => setState(
                  () => _data = ref
                      .read(businessApiProvider)
                      .templates(_search.text),
                ),
              ),
            ),
            onSubmitted: (v) => setState(
              () => _data = ref.read(businessApiProvider).templates(v),
            ),
          ),
        ),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 20),
          child: Text(
            'Reusable content includes tasks saved from archived or deleted jobs. Each copy is a new task with no old due date or completion.',
          ),
        ),
        Expanded(
          child: FutureBuilder<List<TaskTemplate>>(
            future: _data,
            builder: (context, snapshot) {
              if (snapshot.hasError) {
                return Center(
                  child: TextButton(
                    onPressed: () => setState(
                      () => _data = ref
                          .read(businessApiProvider)
                          .templates(_search.text),
                    ),
                    child: const Text('Retry library'),
                  ),
                );
              }
              if (!snapshot.hasData) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snapshot.data!.isEmpty) {
                return const Center(child: Text('No saved tasks yet.'));
              }
              return ListView(
                padding: const EdgeInsets.all(20),
                children: [
                  for (final task in snapshot.data!)
                    Card(
                      child: ListTile(
                        title: Text(task.title),
                        subtitle: Text(
                          '${task.source ?? 'Saved template'} · ${task.chargeType == 'SUPPLIES'
                              ? 'Supplies'
                              : task.chargeType == 'LABOR'
                              ? 'Labor'
                              : 'Unclassified'} · quantity ${taskQuantity(task.quantityUnits)} · ${task.currency}${task.priceMinor == null ? ' · price not set' : ''}',
                        ),
                        onTap: () {
                          final board = ref
                              .read(boardProvider(widget.jobId))
                              .requireValue;
                          bool copyPrice = task.currency == board.currency;
                          showDialog<void>(
                            context: context,
                            barrierDismissible: false,
                            builder: (dialog) => StatefulBuilder(
                              builder: (dialog, update) => AlertDialog(
                                title: Text(task.title),
                                content: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text(task.description),
                                    SwitchListTile(
                                      title: const Text('Copy price'),
                                      subtitle: Text(
                                        task.currency == board.currency
                                            ? 'A new independent task will be created.'
                                            : 'Different currency: copy content without price.',
                                      ),
                                      value: copyPrice,
                                      onChanged: task.currency != board.currency
                                          ? null
                                          : (v) => update(() => copyPrice = v),
                                    ),
                                  ],
                                ),
                                actions: [
                                  BusinessCommand(
                                    label: 'Use template',
                                    create: () {
                                      final id = newBusinessId();
                                      final cardId = newBusinessId();
                                      final price = copyPrice;
                                      return () => ref
                                          .read(businessApiProvider)
                                          .copyTemplate(
                                            id,
                                            board,
                                            task,
                                            cardId,
                                            widget.columnId,
                                            price,
                                          );
                                    },
                                    onCommitted: () {
                                      Navigator.pop(dialog);
                                      Navigator.pop(context);
                                    },
                                  ),
                                ],
                              ),
                            ),
                          );
                        },
                        trailing: IconButton(
                          tooltip: 'Remove reusable template',
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () => showDialog<void>(
                            context: context,
                            barrierDismissible: false,
                            builder: (dialog) => AlertDialog(
                              title: const Text('Remove saved template?'),
                              content: const Text(
                                'Existing task cards keep their content.',
                              ),
                              actions: [
                                BusinessCommand(
                                  label: 'Remove template',
                                  create: () {
                                    final id = newBusinessId();
                                    final version = ref
                                        .read(boardProvider(widget.jobId))
                                        .requireValue
                                        .version;
                                    return () => ref
                                        .read(businessApiProvider)
                                        .removeTemplate(id, version, task);
                                  },
                                  onCommitted: () {
                                    Navigator.pop(dialog);
                                    setState(
                                      () => _data = ref
                                          .read(businessApiProvider)
                                          .templates(_search.text),
                                    );
                                  },
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              );
            },
          ),
        ),
      ],
    ),
  );
}
