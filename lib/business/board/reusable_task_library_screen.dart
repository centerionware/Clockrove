import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/app_localizations.dart';
import '../business_api.dart';
import '../business_command.dart';

class ReusableTaskLibraryScreen extends ConsumerStatefulWidget {
  const ReusableTaskLibraryScreen({
    super.key,
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
    appBar: AppBar(title: Text(AppMessage.reusableTasks.of(context))),
    body: Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(20),
          child: TextField(
            controller: _search,
            decoration: InputDecoration(
              labelText: AppMessage.searchTaskLibrary.of(context),
              suffixIcon: IconButton(
                tooltip: AppMessage.helpSearch.of(context),
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
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Text(AppMessage.reusableLibraryHelp.of(context)),
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
                    child: Text(AppMessage.retryTaskLibrary.of(context)),
                  ),
                );
              }
              if (!snapshot.hasData) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snapshot.data!.isEmpty) {
                return Center(child: Text(AppMessage.noSavedTasks.of(context)));
              }
              return ListView(
                padding: const EdgeInsets.all(20),
                children: [
                  for (final task in snapshot.data!)
                    Card(
                      child: ListTile(
                        title: Text(task.title),
                        subtitle: Text(
                          '${task.source ?? AppMessage.savedTemplate.of(context)} · ${task.chargeType == 'SUPPLIES'
                              ? AppMessage.quoteChargeSupplies.of(context)
                              : task.chargeType == 'LABOR'
                              ? AppMessage.quoteChargeLabor.of(context)
                              : AppMessage.boardUnclassified.of(context)} · ${AppMessage.quoteQuantity.of(context)} ${taskQuantity(task.quantityUnits)} · ${task.currency}${task.priceMinor == null ? ' · ${AppMessage.boardPriceNotSetLong.of(context)}' : ''}',
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
                                      title: Text(
                                        AppMessage.copyTaskPrice.of(dialog),
                                      ),
                                      subtitle: Text(
                                        task.currency == board.currency
                                            ? AppMessage.copyTaskIndependent.of(
                                                dialog,
                                              )
                                            : AppMessage
                                                  .copyTaskDifferentCurrency
                                                  .of(dialog),
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
                                    label: AppMessage.useTaskTemplate.of(
                                      dialog,
                                    ),
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
                          tooltip: AppMessage.removeReusableTemplate.of(
                            context,
                          ),
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () => showDialog<void>(
                            context: context,
                            barrierDismissible: false,
                            builder: (dialog) => AlertDialog(
                              title: Text(
                                AppMessage.removeSavedTemplateQuestion.of(
                                  dialog,
                                ),
                              ),
                              content: Text(
                                AppMessage.existingCardsRetained.of(dialog),
                              ),
                              actions: [
                                BusinessCommand(
                                  label: AppMessage.removeTaskTemplate.of(
                                    dialog,
                                  ),
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
