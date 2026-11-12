import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'quote_api.dart';
import 'quote_document_screen.dart';
import 'quote_editor.dart';
import 'quote_scope_screen.dart';

class QuoteDetailScreen extends ConsumerWidget {
  const QuoteDetailScreen({super.key, required this.quoteId});
  final String quoteId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(quoteProvider(quoteId));
    return value.when(
      skipLoadingOnReload: true,
      skipError: true,
      loading: () => Scaffold(
        appBar: AppBar(title: const Text('Quote')),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Scaffold(
        appBar: AppBar(title: const Text('Quote')),
        body: Center(
          child: TextButton(
            onPressed: () => ref.invalidate(quoteProvider(quoteId)),
            child: const Text('Retry quote details'),
          ),
        ),
      ),
      data: (data) => _QuoteDetail(
        key: ValueKey(quoteId),
        id: quoteId,
        data: data,
        refreshFailed: value.hasError,
      ),
    );
  }
}

class _HistoryPage {
  const _HistoryPage(this.entries, this.more);
  final List<QuoteHistoryEntry> entries;
  final bool more;
}

class _DecisionReview {
  const _DecisionReview(this.version, this.revision, this.decision, this.note);
  final int version, revision;
  final String decision, note;
}

class _QuoteDetail extends ConsumerStatefulWidget {
  const _QuoteDetail({
    super.key,
    required this.id,
    required this.data,
    required this.refreshFailed,
  });
  final String id;
  final QuoteData data;
  final bool refreshFailed;
  @override
  ConsumerState<_QuoteDetail> createState() => _QuoteDetailState();
}

class _QuoteDetailState extends ConsumerState<_QuoteDetail> {
  final _note = TextEditingController();
  String _decision = 'ACCEPTED';
  String? _noteError, _pendingKind;
  int? _issueVersion;
  _DecisionReview? _decisionReview;
  Future<_HistoryPage> _readHistory() async {
    final entries = await ref.read(quoteApiProvider).history(widget.id);
    return _HistoryPage(entries, entries.length == 20);
  }

  late Future<_HistoryPage> _history = _readHistory();
  bool get _pending => _pendingKind != null;
  bool get _eligible => !_pending && !widget.refreshFailed;
  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant _QuoteDetail oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.data.version != widget.data.version) {
      _history = _readHistory();
      if (!_pending) {
        _issueVersion = null;
        _decisionReview = null;
      }
    }
  }

  void _refresh() => ref.invalidate(quoteProvider(widget.id));
  Future<void> _edit() async {
    await Navigator.push<bool>(
      context,
      MaterialPageRoute<bool>(builder: (_) => QuoteEditor(quoteId: widget.id)),
    );
    if (mounted) {
      _refresh();
    }
  }

  Future<void> _scope(QuoteRevision revision) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) =>
            QuoteScopeScreen(quoteId: revision.id, revision: revision.revision),
      ),
    );
    if (mounted) {
      _refresh();
    }
  }

  void _document(QuoteRevision revision) => Navigator.push<void>(
    context,
    MaterialPageRoute<void>(
      builder: (_) => QuoteDocumentScreen(
        quoteId: revision.id,
        revision: revision.revision,
        number: revision.number,
      ),
    ),
  );
  Future<void> _reviewIssue() async {
    final data = widget.data,
        draft = widget.data.draft,
        digits = widget.data.draftDigits;
    if (draft == null || digits == null) {
      return;
    }
    final total = quoteTotals(draft.lines, draft.taxUnits);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          data.latest == null
              ? 'Issue this quote?'
              : 'Issue revision ${data.latest!.revision + 1}?',
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(draft.title, style: Theme.of(context).textTheme.titleMedium),
              Text(
                data.draftCustomer.isEmpty
                    ? 'No customer selected'
                    : 'Customer: ${data.draftCustomer}',
              ),
              Text(
                data.draftEmail.isEmpty
                    ? 'No customer email set; you can enter a recipient in the email app.'
                    : 'Email: ${data.draftEmail}',
              ),
              Text(
                data.draftCompany.isEmpty
                    ? 'Company name is blank in Settings.'
                    : 'Company: ${data.draftCompany}',
              ),
              if (data.draftStyle.isNotEmpty)
                Text('Document style: ${data.draftStyle.toLowerCase()}'),
              if (data.latest != null)
                Text(
                  'Quote ${data.latest!.number} keeps its number. Previous revisions and their decisions stay in history.',
                ),
              const Text(
                'This freezes the estimate. It does not record acceptance, worked hours, completed tasks, an invoice or a payment.',
              ),
              const SizedBox(height: 16),
              for (final line in draft.lines)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(
                    '${line.title}\n${taskQuantity(line.quantity)} ${line.kind == 'HOURS' ? 'estimated hours' : 'units'} × ${draft.currency} ${money(line.unitPrice, digits)}\nLine total ${draft.currency} ${money(taskAmount(line.unitPrice, line.quantity), digits)}',
                  ),
                ),
              Text(
                'Subtotal ${draft.currency} ${money(total.subtotal, digits)}',
              ),
              Text('Tax ${draft.currency} ${money(total.tax, digits)}'),
              Text(
                'Estimated total ${draft.currency} ${money(total.total, digits)}',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              Text(
                draft.validUntil == null
                    ? 'No expiry date'
                    : 'Valid until ${draft.validUntil} (calendar date)',
              ),
              if (draft.terms.isNotEmpty) Text('Terms\n${draft.terms}'),
              if (draft.notes.isNotEmpty) Text('Printed notes\n${draft.notes}'),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Keep editing'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirm review'),
          ),
        ],
      ),
    );
    if (mounted && confirmed == true) {
      setState(() => _issueVersion = data.version);
    }
  }

  Future<void> _reviewDecision() async {
    final data = widget.data, revision = widget.data.latest;
    if (revision == null) {
      return;
    }
    if (_note.text.trim().length < 10 ||
        _note.text.length > 2000 ||
        _note.text.contains('\u0000')) {
      setState(
        () => _noteError = 'Add a note of 10–2000 characters describing how this revision was accepted or declined.',
      );
      return;
    }
    final review = _DecisionReview(
      data.version,
      revision.revision,
      _decision,
      _note.text.trim(),
    );
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          '${quoteStatus(review.decision)} · revision ${review.revision}?',
        ),
        content: SingleChildScrollView(
          child: Text(
            '${revision.number} · ${revision.draft.title}\n${revision.draft.currency} ${revision.total}\n\n${revision.draft.validUntil == null ? 'No expiry date set.' : 'Validity date: ${revision.draft.validUntil}.'}\n\n${review.note}\n\nThis is your explicit local record against this revision. An email draft is not proof of customer acceptance. The record is retained; changed terms require a new issued revision.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirm review'),
          ),
        ],
      ),
    );
    if (mounted && confirmed == true) {
      setState(() {
        _decisionReview = review;
        _noteError = null;
      });
    }
  }

  Future<void> _historical(int revision) async {
    final future = ref.read(quoteApiProvider).revision(widget.id, revision);
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Revision $revision'),
        content: FutureBuilder<QuoteRevision?>(
          future: future,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return const Text(
                'This revision could not be loaded. Close and try again.',
              );
            }
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const SizedBox(
                height: 80,
                child: Center(child: CircularProgressIndicator()),
              );
            }
            final value = snapshot.data;
            if (value == null) {
              return const Text('This revision is unavailable.');
            }
            return SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${value.number} · ${value.draft.title}'),
                  Text('${value.draft.currency} ${value.total}'),
                  Text(
                    value.decision == null
                        ? 'No local decision recorded'
                        : quoteStatus(value.decision!),
                  ),
                  if (value.decisionNote != null)
                    Text('Private local note\n${value.decisionNote}'),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: () {
                      Navigator.pop(context);
                      _document(value);
                    },
                    icon: const Icon(Icons.picture_as_pdf_outlined),
                    label: const Text('Open revision PDF'),
                  ),
                  if (value.decision == 'ACCEPTED')
                    OutlinedButton(
                      onPressed: () {
                        Navigator.pop(context);
                        _scope(value);
                      },
                      child: const Text('Review accepted revision scope'),
                    ),
                ],
              ),
            );
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final data = widget.data,
        latest = widget.data.latest,
        draft = widget.data.draft;
    final expired =
        latest != null &&
        latest.decision == null &&
        latest.draft.validUntil != null &&
        data.today.isNotEmpty &&
        latest.draft.validUntil!.compareTo(data.today) < 0;
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: Text(latest?.number ?? 'Quote draft'),
          actions: [
            IconButton(
              tooltip: 'Refresh quote details',
              onPressed: _pending ? null : _refresh,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: ListView(
          padding: EdgeInsets.fromLTRB(
            24,
            16,
            24,
            24 + MediaQuery.paddingOf(context).bottom,
          ),
          children: [
            if (draft == null && latest == null)
              const Text('This quote is unavailable.'),
            if (widget.refreshFailed)
              Text(
                'Refresh failed. Saved information is shown; refresh before changing this quote.',
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            if (latest != null) ...[
              Text(
                latest.draft.title,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              Text(
                'Revision ${latest.revision} · ${quoteStatus(latest.decision ?? (expired ? 'EXPIRED' : 'ISSUED'))}',
              ),
              if (data.today.isNotEmpty)
                Text('As of ${data.today} · ${data.timezone}'),
              if (latest.customer.isNotEmpty) Text(latest.customer),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Estimated total',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(
                        '${latest.draft.currency} ${latest.total}',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      Text('Subtotal ${latest.subtotal} · Tax ${latest.tax}'),
                      if (latest.draft.validUntil != null)
                        Text(
                          'Valid until ${latest.draft.validUntil} (calendar date)',
                        ),
                      const Text('Estimate only · no payment due'),
                    ],
                  ),
                ),
              ),
              FilledButton.icon(
                onPressed: _pending ? null : () => _document(latest),
                icon: const Icon(Icons.picture_as_pdf_outlined),
                label: const Text('Open issued quote'),
              ),
              if (latest.decision == 'ACCEPTED')
                OutlinedButton.icon(
                  onPressed: _eligible ? () => _scope(latest) : null,
                  icon: const Icon(Icons.playlist_add_check),
                  label: const Text('Plan accepted scope'),
                ),
            ],
            const SizedBox(height: 16),
            if (draft != null || latest != null)
              OutlinedButton.icon(
                onPressed: _eligible ? _edit : null,
                icon: const Icon(Icons.edit_outlined),
                label: Text(
                  draft != null ? 'Edit draft' : 'Create revised draft',
                ),
              ),
            if (draft != null || _pendingKind == 'issue')
              Card(
                key: ValueKey('quote-issue-card/${widget.id}'),
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        'Editable draft',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      Text(
                        draft?.title ??
                            'Checking the captured issue operation…',
                      ),
                      const Text(
                        'Review all prices, tax, terms and customer information before issuing. New revisions require their own local decision.',
                      ),
                      const SizedBox(height: 12),
                      OutlinedButton(
                        onPressed:
                            _eligible &&
                                draft != null &&
                                data.draftDigits != null
                            ? _reviewIssue
                            : null,
                        child: const Text('Review quote for issue'),
                      ),
                      BusinessCommand(
                        key: ValueKey('issue/${widget.id}'),
                        label: latest == null
                            ? 'Issue quote'
                            : 'Issue next revision',
                        enabled:
                            _eligible &&
                            draft != null &&
                            _issueVersion == data.version,
                        onPendingChanged: (value) => setState(
                          () => _pendingKind = value ? 'issue' : null,
                        ),
                        create: () {
                          final version = _issueVersion;
                          if (version == null ||
                              version != data.version ||
                              draft == null) {
                            throw const FormatException(
                              'Review the current quote draft before issuing.',
                            );
                          }
                          final id = newBusinessId(),
                              api = ref.read(quoteApiProvider),
                              quoteId = widget.id;
                          return () => api.issue(id, version, quoteId);
                        },
                        onCommitted: _refresh,
                      ),
                    ],
                  ),
                ),
              ),
            if (latest != null)
              Card(
                key: ValueKey('quote-decision-card/${widget.id}'),
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        'Local acceptance record',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (latest.decision != null &&
                          _pendingKind != 'decision') ...[
                        Text(quoteStatus(latest.decision!)),
                        if (latest.decisionNote != null)
                          Text(latest.decisionNote!),
                        const Text(
                          'This record belongs to this issued revision. Changed terms require a new revision.',
                        ),
                      ] else ...[
                        const Text(
                          'Record the customer’s decision explicitly. This private note is not printed in the issued PDF.',
                        ),
                        const SizedBox(height: 12),
                        DropdownButtonFormField<String>(
                          initialValue: _decision,
                          decoration: const InputDecoration(
                            labelText: 'Customer decision',
                          ),
                          items: const [
                            DropdownMenuItem(
                              value: 'ACCEPTED',
                              child: Text('Accepted'),
                            ),
                            DropdownMenuItem(
                              value: 'DECLINED',
                              child: Text('Declined'),
                            ),
                          ],
                          onChanged: !_eligible
                              ? null
                              : (value) {
                                  if (value != null) {
                                    setState(() {
                                      _decision = value;
                                      _decisionReview = null;
                                    });
                                  }
                                },
                        ),
                        const SizedBox(height: 12),
                        TextFormField(
                          controller: _note,
                          enabled: _eligible,
                          minLines: 2,
                          maxLines: 5,
                          maxLength: 2000,
                          maxLengthEnforcement: MaxLengthEnforcement.none,
                          decoration: InputDecoration(
                            labelText: 'Private decision note',
                            errorText: _noteError,
                          ),
                          onChanged: (_) => setState(() {
                            _decisionReview = null;
                            _noteError = null;
                          }),
                        ),
                        OutlinedButton(
                          onPressed: _eligible && latest.decision == null
                              ? _reviewDecision
                              : null,
                          child: const Text('Review customer decision'),
                        ),
                        BusinessCommand(
                          key: ValueKey('decision/${widget.id}'),
                          label: 'Record reviewed decision',
                          enabled:
                              _eligible &&
                              latest.decision == null &&
                              _decisionReview?.version == data.version,
                          onPendingChanged: (value) => setState(
                            () => _pendingKind = value ? 'decision' : null,
                          ),
                          create: () {
                            final review = _decisionReview;
                            if (review == null ||
                                review.version != data.version ||
                                review.revision != latest.revision) {
                              throw const FormatException(
                                'Review the current issued revision and decision first.',
                              );
                            }
                            final id = newBusinessId(),
                                api = ref.read(quoteApiProvider),
                                quoteId = widget.id;
                            return () => api.decide(
                              id,
                              review.version,
                              quoteId,
                              review.revision,
                              review.decision,
                              review.note,
                            );
                          },
                          onCommitted: _refresh,
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            if (latest != null) ...[
              const SizedBox(height: 20),
              Text(
                'Revision history',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              FutureBuilder<_HistoryPage>(
                future: _history,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return TextButton(
                      onPressed: () => setState(() {
                        _history = _readHistory();
                      }),
                      child: const Text('Retry revision history'),
                    );
                  }
                  final entries =
                      snapshot.data?.entries ?? const <QuoteHistoryEntry>[];
                  return Column(
                    children: [
                      if (snapshot.connectionState == ConnectionState.waiting)
                        const LinearProgressIndicator(),
                      for (final entry in entries)
                        ListTile(
                          title: Text('Revision ${entry.revision}'),
                          subtitle: Text(
                            '${entry.decision == null ? 'No decision recorded' : quoteStatus(entry.decision!)}\n${DateTime.fromMillisecondsSinceEpoch(entry.created, isUtc: true).toIso8601String()} UTC',
                          ),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: _pending
                              ? null
                              : () => _historical(entry.revision),
                        ),
                      if (snapshot.data?.more == true)
                        TextButton(
                          onPressed:
                              _pending ||
                                  snapshot.connectionState ==
                                      ConnectionState.waiting
                              ? null
                              : () {
                                  final previous = _history,
                                      before = entries.last.revision,
                                      api = ref.read(quoteApiProvider),
                                      id = widget.id;
                                  setState(
                                    () =>
                                        _history = previous.then((value) async {
                                          final older = await api.history(
                                            id,
                                            before: before,
                                          );
                                          return _HistoryPage([
                                            ...value.entries,
                                            ...older,
                                          ], older.length == 20);
                                        }),
                                  );
                                },
                          child: const Text('Load older revisions'),
                        ),
                    ],
                  );
                },
              ),
            ],
          ],
        ),
      ),
    );
  }
}
