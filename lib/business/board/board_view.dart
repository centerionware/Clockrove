import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/app_localizations.dart';
import '../../tracking/history_screen.dart';
import '../business_api.dart';
import '../business_command.dart';
import '../job_settings_screen.dart';
import '../report_screen.dart';
import '../settings_validation_messages.dart';
import '../task_conversation_screen.dart';
import 'reusable_task_library_screen.dart';

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
            tooltip: AppMessage.historyListTitle.of(context),
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
            tooltip: AppMessage.workReport.of(context),
            icon: const Icon(Icons.assessment_outlined),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => ReportScreen(jobId: jobId),
              ),
            ),
          ),
          IconButton(
            tooltip: AppMessage.jobSettings.of(context),
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
            child: Text(AppMessage.retryJobSettings.of(context)),
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
    final latest = ref.read(boardProvider(board.jobId)).value ?? board;
    final moving = latest.cards.where((c) => c.id == card.id).firstOrNull;
    final target = latest.columns
        .where((column) => column.id == destination)
        .firstOrNull;
    if (moving == null || target == null || latest.archived) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppMessage.boardMoveUnavailable.of(context))),
      );
      return;
    }

    final count = latest
        .inColumn(destination)
        .where((candidate) => candidate.id != card.id)
        .length;
    try {
      final result = await ref
          .read(businessApiProvider)
          .moveCard(
            newBusinessId(),
            latest,
            moving,
            destination,
            position.clamp(0, count),
          );
      if (!context.mounted || result.committed) return;
      ref.invalidate(boardProvider(board.jobId));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppMessage.boardChangedMoveAgain.of(context))),
      );
    } catch (_) {
      if (!context.mounted) return;
      ref.invalidate(boardProvider(board.jobId));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppMessage.boardMoveUnconfirmed.of(context))),
      );
    }
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
              if (card.dueDate != null)
                Text(
                  AppMessage.boardDueDate.format(context, {
                    'date': card.dueDate!,
                  }),
                ),
              Text(
                !card.billable
                    ? AppMessage.boardNonbillable.of(context)
                    : card.priceMinor == null
                    ? AppMessage.boardPriceNotSet.of(context)
                    : '${board.currency} ${money(card.amountMinor, board.digits)}',
              ),
              if (card.priceMinor != null)
                Text(
                  AppMessage.boardTaskUnitPrice.format(context, {
                    'quantity': taskQuantity(card.quantityUnits),
                    'currency': card.currency,
                    'amount': money(card.priceMinor, board.digits),
                  }),
                ),
              if (card.chargeType == 'SUPPLIES')
                Text(AppMessage.quoteChargeSupplies.of(context)),
              if (card.chargeType == 'UNCLASSIFIED')
                Text(AppMessage.boardUnclassifiedHelp.of(context)),
              if (card.completedMillis != null)
                Text(AppMessage.boardCompleted.of(context)),
            ],
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: AppMessage.taskConversationTitle.of(context),
              icon: const Icon(Icons.forum_outlined),
              onPressed: () => _conversation(context, card),
            ),
            if (!board.archived)
              PopupMenuButton<String>(
                tooltip: AppMessage.boardTaskActions.of(context),
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
                  PopupMenuItem(
                    value: 'edit',
                    child: Text(AppMessage.boardEditTask.of(context)),
                  ),
                  if (index > 0)
                    PopupMenuItem(
                      value: 'up',
                      child: Text(AppMessage.boardMoveUp.of(context)),
                    ),
                  if (index + 1 < inColumn.length)
                    PopupMenuItem(
                      value: 'down',
                      child: Text(AppMessage.boardMoveDown.of(context)),
                    ),
                  for (final column in board.columns.where(
                    (c) => c.id != card.columnId,
                  ))
                    PopupMenuItem(
                      value: column.id,
                      child: Text(
                        AppMessage.boardMoveTo.format(context, {
                          'column': column.name,
                        }),
                      ),
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
                  AppMessage.boardTitle.of(context),
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                Text(
                  AppMessage.boardTaskProgress.format(context, {
                    'complete':
                        '${board.cards.where((c) => c.completedMillis != null).length}',
                    'total': '${board.cards.length}',
                  }),
                ),
              ],
            ),
            if (!board.archived)
              OutlinedButton.icon(
                onPressed: () => _editColumn(context),
                icon: const Icon(Icons.add),
                label: Text(AppMessage.boardColumn.of(context)),
              ),
          ],
        ),
      ),
      if (board.archived)
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Text(AppMessage.boardArchivedReadOnly.of(context)),
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
                                      ? AppMessage.boardCompletesTasks.of(
                                          context,
                                        )
                                      : AppMessage.boardTaskCount.format(
                                          context,
                                          {
                                            'count':
                                                '${board.inColumn(column.id).length}',
                                          },
                                        ),
                                ),
                                trailing: board.archived
                                    ? null
                                    : IconButton(
                                        tooltip: AppMessage.boardEditColumn.of(
                                          context,
                                        ),
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
                                                Padding(
                                                  padding: EdgeInsets.all(12),
                                                  child: Text(
                                                    AppMessage.boardDropToMove
                                                        .of(context),
                                                  ),
                                                ),
                                              TextButton.icon(
                                                onPressed: () =>
                                                    _task(context, column.id),
                                                icon: const Icon(Icons.add),
                                                label: Text(
                                                  AppMessage.boardAddTask.of(
                                                    context,
                                                  ),
                                                ),
                                              ),
                                              TextButton.icon(
                                                onPressed: () => Navigator.push(
                                                  context,
                                                  MaterialPageRoute<void>(
                                                    builder: (_) =>
                                                        ReusableTaskLibraryScreen(
                                                          jobId: board.jobId,
                                                          columnId: column.id,
                                                        ),
                                                  ),
                                                ),
                                                icon: const Icon(
                                                  Icons.copy_outlined,
                                                ),
                                                label: Text(
                                                  AppMessage.boardReuseTask.of(
                                                    context,
                                                  ),
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
          ? AppMessage.boardTaskTotalMissing.of(context)
          : AppMessage.boardTaskTotal.format(context, {
              'currency': board.currency,
              'amount': money(total, board.digits),
            });
    } on FormatException {
      return AppMessage.boardTaskTotalCheck.of(context);
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
        title: Text(
          widget.card == null
              ? AppMessage.boardNewTask.of(context)
              : AppMessage.boardEditTask.of(context),
        ),
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
                      decoration: InputDecoration(
                        labelText: AppMessage.boardTaskTitle.of(context),
                      ),
                      maxLength: 240,
                      validator: (v) => v == null || v.trim().isEmpty
                          ? AppMessage.boardTaskTitleRequired.of(context)
                          : null,
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      controller: _description,
                      decoration: InputDecoration(
                        labelText: AppMessage.quoteLineDescription.of(context),
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
                          labelText: AppMessage.boardUnitPrice.format(context, {
                            'currency': board.currency,
                          }),
                          helperText: AppMessage.boardPriceEntryHelp.of(
                            context,
                          ),
                        ),
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        validator: (v) {
                          try {
                            parseMoney(v ?? '', board.digits);
                            return null;
                          } on FormatException catch (e) {
                            return localizedBusinessFormatError(context, e);
                          }
                        },
                      ),
                    ),
                    const SizedBox(height: 16),
                    Semantics(
                      identifier: 'task-quantity',
                      child: TextFormField(
                        controller: _quantity,
                        decoration: InputDecoration(
                          labelText: AppMessage.quoteQuantity.of(context),
                          helperText: AppMessage.boardQuantityHelp.of(context),
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
                            return localizedBusinessFormatError(context, e);
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
                      title: Text(AppMessage.boardBillableTask.of(context)),
                      value: _billable,
                      onChanged: (v) => setState(() => _billable = v),
                    ),
                    if (_chargeType == 'UNCLASSIFIED') ...[
                      const Align(
                        alignment: Alignment.centerLeft,
                        child: Text(
                          AppMessage.boardClassificationPrompt.of(context),
                        ),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        children: [
                          ChoiceChip(
                            label: Text(
                              AppMessage.quoteChargeLabor.of(context),
                            ),
                            selected: _chargeType == 'LABOR',
                            onSelected: (_) =>
                                setState(() => _chargeType = 'LABOR'),
                          ),
                          ChoiceChip(
                            label: Text(
                              AppMessage.quoteChargeSupplies.of(context),
                            ),
                            selected: _chargeType == 'SUPPLIES',
                            onSelected: (_) =>
                                setState(() => _chargeType = 'SUPPLIES'),
                          ),
                        ],
                      ),
                    ] else
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(AppMessage.quoteChargeSupplies.of(context)),
                        subtitle: Text(
                          AppMessage.boardSuppliesHelp.of(context),
                        ),
                        value: _chargeType == 'SUPPLIES',
                        onChanged: (value) => setState(
                          () => _chargeType = value ? 'SUPPLIES' : 'LABOR',
                        ),
                      ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.calendar_today_outlined),
                      title: Text(
                        _due ?? AppMessage.boardNoDueDate.of(context),
                      ),
                      onTap: _pickDate,
                      trailing: _due == null
                          ? null
                          : IconButton(
                              tooltip: AppMessage.boardRemoveDueDate.of(
                                context,
                              ),
                              icon: const Icon(Icons.close),
                              onPressed: () => setState(() => _due = null),
                            ),
                    ),
                    const SizedBox(height: 24),
                    BusinessCommand(
                      label: AppMessage.save.of(context),
                      create: () {
                        if (!_form.currentState!.validate()) {
                          throw FormatException(
                            AppMessage.boardCheckFields.of(context),
                          );
                        }
                        if (_chargeType == 'UNCLASSIFIED') {
                          throw FormatException(
                            AppMessage.boardChooseChargeType.of(context),
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
                              title: Text(
                                AppMessage.boardDeleteTaskQuestion.of(dialog),
                              ),
                              content: Text(
                                AppMessage.boardKeepReusableTemplate.of(dialog),
                              ),
                              actions: [
                                BusinessCommand(
                                  label: AppMessage.boardDeleteTask.of(dialog),
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
                          label: Text(AppMessage.boardDeleteTask.of(context)),
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
        title: Text(
          column == null
              ? AppMessage.boardNewColumn.of(context)
              : AppMessage.boardColumnSettings.of(context),
        ),
      ),
      body: board == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(24),
              children: [
                TextField(
                  controller: _name,
                  maxLength: 120,
                  decoration: InputDecoration(
                    labelText: AppMessage.boardColumnName.of(context),
                  ),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(AppMessage.boardColumnCompleteTitle.of(context)),
                  subtitle: Text(
                    AppMessage.boardColumnCompleteHelp.of(context),
                  ),
                  value: _completed,
                  onChanged: (v) => setState(() => _completed = v),
                ),
                const SizedBox(height: 16),
                BusinessCommand(
                  label: AppMessage.save.of(context),
                  create: () {
                    if (_name.text.trim().isEmpty) {
                      throw FormatException(
                        AppMessage.boardColumnNameRequired.of(context),
                      );
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
                    AppMessage.boardColumnOrder.of(context),
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
                              ? AppMessage.boardMoveColumnLeft.of(context)
                              : AppMessage.boardMoveColumnRight.of(context),
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
                      AppMessage.boardDeleteColumn.of(context),
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                    if (board.inColumn(column.id).isNotEmpty)
                      DropdownButtonFormField<String>(
                        initialValue: _destination,
                        isExpanded: true,
                        decoration: InputDecoration(
                          labelText: AppMessage.boardMoveTasksTo.of(context),
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
                      label: AppMessage.boardDeleteColumnMoveTasks.of(context),
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
