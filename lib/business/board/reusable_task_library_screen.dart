import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../business_api.dart';
import '../business_command.dart';

class ReusableTaskLibraryScreen extends ConsumerStatefulWidget {
  const ReusableTaskLibraryScreen({
    required this.jobId,
    required this.columnId,
  });
  final String jobId, columnId;
  @override
  ConsumerState<ReusableTaskLibraryScreen> createState() => _TemplatesState();
}

class _TemplatesState extends ConsumerState<ReusableTaskLibraryScreen> {
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
