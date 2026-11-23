import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'job_screen.dart';
import 'quote_scope_api.dart';
import 'quote_scope_form_screen.dart';
import 'tax_rate.dart';

class QuoteScopeScreen extends ConsumerStatefulWidget {
  const QuoteScopeScreen({
    super.key,
    required this.quoteId,
    required this.revision,
    this.createNewJob = false,
  });
  final String quoteId;
  final int revision;
  final bool createNewJob;
  @override
  ConsumerState<QuoteScopeScreen> createState() => _QuoteScopeScreenState();
}

class _QuoteScopeScreenState extends ConsumerState<QuoteScopeScreen> {
  late Future<QuoteScopeData> _data = ref
      .read(quoteScopeApiProvider)
      .read(widget.quoteId, widget.revision);
  void _refresh() => setState(() {
    _data = ref
        .read(quoteScopeApiProvider)
        .read(widget.quoteId, widget.revision);
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(
        widget.createNewJob
            ? AppMessage.quoteCreateJob.of(context)
            : AppMessage.reviewAcceptedScope.of(context),
      ),
      actions: [
        IconButton(
          tooltip: AppMessage.quoteScopeRefreshGroups.of(context),
          onPressed: _refresh,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: FutureBuilder<QuoteScopeData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: TextButton(
              onPressed: _refresh,
              child: Text(AppMessage.quoteScopeRetryAccepted.of(context)),
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snapshot.data!, source = snapshot.data!.issued.draft;
        if (data.issued.decision != 'ACCEPTED') {
          return Center(
            child: Text(AppMessage.quoteScopeAcceptanceRequired.of(context)),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              AppMessage.quoteInvoiceRevisionRef.format(context, {
                'number': data.issued.number,
                'revision': data.issued.revision.toString(),
              }),
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            Text(source.title),
            const SizedBox(height: 12),
            Text(AppMessage.quoteScopeGroupGuidance.of(context)),
            Text(AppMessage.quoteScopeNoWorkSideEffects.of(context)),
            if (widget.createNewJob && quoteScopeGroups(source).length > 1)
              Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(AppMessage.quoteScopeMultipleGroups.of(context)),
              ),
            if (data.historical)
              Text(
                AppMessage.quoteScopeHistoricalNotice.format(context, {
                  'revision': data.latestRevision.toString(),
                }),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            const SizedBox(height: 16),
            for (final group in quoteScopeGroups(source))
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        group.kind == 'ITEM'
                            ? AppMessage.quoteScopeFixedPriceItems.of(context)
                            : AppMessage.estimatedHours.of(context),
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (group.rate != null)
                        Text(
                          AppMessage.quoteScopeAgreedRate.format(context, {
                            'currency': source.currency,
                            'rate': money(group.rate, data.issued.digits),
                          }),
                        ),
                      for (final line in group.lines)
                        Text(
                          AppMessage.quoteScopeLineSummary.format(context, {
                            'title': line.title,
                            'quantity': taskQuantity(line.quantity),
                            'unit': group.kind == 'HOURS'
                                ? AppMessage.estimatedHours.of(context)
                                : AppMessage.quoteUnitsUnit.of(context),
                          }),
                        ),
                      const SizedBox(height: 12),
                      FilledButton.icon(
                        onPressed: () => _openGroup(
                          context,
                          data,
                          group,
                          createNewJob: widget.createNewJob,
                        ),
                        icon: Icon(
                          widget.createNewJob
                              ? Icons.add_business_outlined
                              : Icons.playlist_add_check,
                        ),
                        label: Text(
                          widget.createNewJob
                              ? AppMessage.quoteScopeCreateTasksJob.of(context)
                              : AppMessage.quoteScopeReviewGroupPlan.of(context),
                        ),
                      ),
                      if (!widget.createNewJob) ...[
                        const SizedBox(height: 8),
                        OutlinedButton(
                          onPressed: () => _openGroup(
                            context,
                            data,
                            group,
                            createNewJob: true,
                          ),
                          child: Text(
                            AppMessage.quoteScopeCreateNewTasksJob.of(context),
                          ),
                        ),
                      ],
                      for (final jobId
                          in data.bindings
                              .where(
                                (binding) => group.lines.any(
                                  (line) => line.id == binding.lineId,
                                ),
                              )
                              .map((binding) => binding.jobId)
                              .toSet()) ...[
                        Builder(
                          builder: (context) {
                            final job = data.jobs
                                .where((candidate) => candidate.id == jobId)
                                .firstOrNull;
                            if (job == null) {
                              return Text(
                                AppMessage.quoteScopeMappedJobUnavailable.of(context),
                              );
                            }
                            return Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                const SizedBox(height: 8),
                                Text(AppMessage.quoteScopePlannedIn.format(context, {
                                  'job': job.name,
                                })),
                                OutlinedButton.icon(
                                  onPressed: () => Navigator.push<void>(
                                    context,
                                    MaterialPageRoute<void>(
                                      builder: (_) => JobScreen(
                                        jobId: job.id,
                                        jobName: job.name,
                                      ),
                                    ),
                                  ),
                                  icon: const Icon(Icons.view_kanban_outlined),
                                  label: Text(AppMessage.quoteScopeOpenExistingJob.of(context)),
                                ),
                              ],
                            );
                          },
                        ),
                      ],
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    ),
  );

  Future<void> _openGroup(
    BuildContext context,
    QuoteScopeData data,
    QuoteScopeGroup group, {
    required bool createNewJob,
  }) async {
    await Navigator.push<bool>(
      context,
      MaterialPageRoute<bool>(
        builder: (_) => QuoteScopeFormScreen(
          initial: data,
          groupKey: group.key,
          createNewJob: createNewJob,
        ),
      ),
    );
    if (mounted) _refresh();
  }
}
