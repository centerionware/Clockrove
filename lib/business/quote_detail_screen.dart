import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'quote_api.dart';
import 'quote_document_screen.dart';
import 'quote_editor.dart';
import 'quote_scope_screen.dart';
import 'quote_invoice_screen.dart';
import 'quote_related_invoices_screen.dart';

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
        appBar: AppBar(title: Text(AppMessage.quoteDetailTitle.of(context))),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Scaffold(
        appBar: AppBar(title: Text(AppMessage.quoteDetailTitle.of(context))),
        body: Center(
          child: TextButton(
            onPressed: () => ref.invalidate(quoteProvider(quoteId)),
            child: Text(AppMessage.quoteDetailRetry.of(context)),
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

  Future<void> _scope(
    QuoteRevision revision, {
    bool createNewJob = false,
  }) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) => QuoteScopeScreen(
          quoteId: revision.id,
          revision: revision.revision,
          createNewJob: createNewJob,
        ),
      ),
    );
    if (mounted) {
      _refresh();
    }
  }

  Future<void> _invoiceQuote(QuoteRevision revision) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) => QuoteInvoiceScreen(
          quoteId: revision.id,
          revision: revision.revision,
        ),
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
    late final ({String subtotal, String tax, String total}) total;
    try {
      total = quoteTotals(draft.lines, draft.taxUnits);
    } on QuoteValidationException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.messageKey.of(context))));
      }
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          data.latest == null
              ? AppMessage.quoteIssueQuestion.of(context)
              : AppMessage.quoteIssueRevisionQuestion.format(context, {
                  'revision': (data.latest!.revision + 1).toString(),
                }),
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(draft.title, style: Theme.of(context).textTheme.titleMedium),
              Text(
                data.draftCustomer.isEmpty
                    ? AppMessage.noCustomer.of(context)
                    : AppMessage.quoteInvoiceReviewCustomer.format(context, {
                        'customer': data.draftCustomer,
                      }),
              ),
              Text(
                data.draftEmail.isEmpty
                    ? AppMessage.quoteIssueEmailMissing.of(context)
                    : AppMessage.quoteInvoiceReviewEmail.format(context, {
                        'email': data.draftEmail,
                      }),
              ),
              Text(
                data.draftCompany.isEmpty
                    ? AppMessage.quoteIssueCompanyMissing.of(context)
                    : AppMessage.quoteIssueCompany.format(context, {
                        'company': data.draftCompany,
                      }),
              ),
              if (data.draftStyle.isNotEmpty)
                Text(
                  AppMessage.quoteIssueDocumentStyle.format(context, {
                    'style': switch (data.draftStyle) {
                      'CLASSIC' => AppMessage.invoiceStyleClassic.of(context),
                      'MODERN' => AppMessage.invoiceStyleModern.of(context),
                      'MINIMAL' => AppMessage.invoiceStyleMinimal.of(context),
                      _ => AppMessage.invoiceStyle.of(context),
                    },
                  }),
                ),
              if (data.latest != null)
                Text(
                  AppMessage.quoteIssueRevisionHistory.format(context, {
                    'number': data.latest!.number,
                  }),
                ),
              Text(AppMessage.quoteIssueFreezeNotice.of(context)),
              const SizedBox(height: 16),
              for (final line in draft.lines)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(
                    AppMessage.quoteIssueLineSummary.format(context, {
                      'title': line.title,
                      'quantity': taskQuantity(line.quantity),
                      'unit': line.kind == 'HOURS'
                          ? AppMessage.estimatedHours.of(context)
                          : AppMessage.quoteUnitsUnit.of(context),
                      'currency': draft.currency,
                      'price': money(line.unitPrice, digits),
                      'amount': money(
                        taskAmount(line.unitPrice, line.quantity),
                        digits,
                      ),
                    }),
                  ),
                ),
              Text(
                AppMessage.invoiceAmountLine.format(context, {
                  'label': AppMessage.invoiceSubtotal.of(context),
                  'amount':
                      '${draft.currency} ${money(total.subtotal, digits)}',
                }),
              ),
              Text(
                AppMessage.invoiceAmountLine.format(context, {
                  'label': AppMessage.invoiceTax.of(context),
                  'amount': '${draft.currency} ${money(total.tax, digits)}',
                }),
              ),
              Text(
                AppMessage.invoiceAmountLine.format(context, {
                  'label': AppMessage.quoteEstimatedTotalLabel.of(context),
                  'amount': '${draft.currency} ${money(total.total, digits)}',
                }),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              Text(
                draft.validUntil == null
                    ? AppMessage.quoteIssueNoExpiry.of(context)
                    : AppMessage.quoteIssueValidUntil.format(context, {
                        'date': draft.validUntil!,
                      }),
              ),
              if (draft.terms.isNotEmpty)
                Text(
                  AppMessage.quoteIssueTerms.format(context, {
                    'terms': draft.terms,
                  }),
                ),
              if (draft.notes.isNotEmpty)
                Text(
                  AppMessage.quoteIssuePrintedNotes.format(context, {
                    'notes': draft.notes,
                  }),
                ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.keepEditing.of(context)),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.confirmReview.of(context)),
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
    if (_note.text.length > 2000 || _note.text.contains('\u0000')) {
      setState(
        () => _noteError = AppMessage.quoteFieldCharacterLimit.format(context, {
          'limit': '2000',
        }),
      );
      return;
    }
    final review = _DecisionReview(
      data.version,
      revision.revision,
      _decision,
      _note.text.trim(),
    );
    // Dismiss the note IME before showing the confirmation. On Android 15,
    // restoring focus when the dialog closes can reopen it over the record
    // action and leave the reviewed decision hidden.
    FocusManager.instance.primaryFocus?.unfocus();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          AppMessage.quoteDecisionReviewQuestion.format(context, {
            'decision': quoteStatusMessage(review.decision).of(context),
            'revision': review.revision.toString(),
          }),
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('${revision.number} · ${revision.draft.title}'),
              Text('${revision.draft.currency} ${revision.total}'),
              Text(
                revision.draft.validUntil == null
                    ? AppMessage.quoteDecisionNoExpiry.of(context)
                    : AppMessage.quoteDecisionValidity.format(context, {
                        'date': revision.draft.validUntil!,
                      }),
              ),
              if (review.note.isNotEmpty)
                Text(
                  AppMessage.quoteDecisionPrivateNote.format(context, {
                    'note': review.note,
                  }),
                ),
              Text(AppMessage.quoteDecisionRecordNotice.of(context)),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.cancel.of(context)),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.confirmReview.of(context)),
          ),
        ],
      ),
    );
    if (mounted && confirmed == true) {
      FocusManager.instance.primaryFocus?.unfocus();
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
        title: Text(
          AppMessage.quoteRevisionTitle.format(context, {
            'revision': revision.toString(),
          }),
        ),
        content: FutureBuilder<QuoteRevision?>(
          future: future,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return Text(AppMessage.quoteRevisionReadFailed.of(context));
            }
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const SizedBox(
                height: 80,
                child: Center(child: CircularProgressIndicator()),
              );
            }
            final value = snapshot.data;
            if (value == null) {
              return Text(AppMessage.quoteRevisionUnavailable.of(context));
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
                        ? AppMessage.quoteNoDecisionRecorded.of(context)
                        : quoteStatusMessage(value.decision!).of(context),
                  ),
                  if (value.decisionNote?.isNotEmpty == true)
                    Text(
                      AppMessage.quotePrivateLocalNote.format(context, {
                        'note': value.decisionNote!,
                      }),
                    ),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: () {
                      Navigator.pop(context);
                      _document(value);
                    },
                    icon: const Icon(Icons.picture_as_pdf_outlined),
                    label: Text(AppMessage.quoteOpenRevisionPdf.of(context)),
                  ),
                  if (value.decision == 'ACCEPTED')
                    OutlinedButton(
                      onPressed: () {
                        Navigator.pop(context);
                        _scope(value);
                      },
                      child: Text(AppMessage.reviewAcceptedScope.of(context)),
                    ),
                  if (value.decision == 'ACCEPTED')
                    OutlinedButton(
                      onPressed: () {
                        Navigator.pop(context);
                        _invoiceQuote(value);
                      },
                      child: Text(AppMessage.invoiceActualWork.of(context)),
                    ),
                ],
              ),
            );
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(AppMessage.close.of(context)),
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
          title: Text(latest?.number ?? AppMessage.quoteDraft.of(context)),
          actions: [
            IconButton(
              tooltip: AppMessage.quoteDetailRefresh.of(context),
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
              Text(AppMessage.quoteUnavailable.of(context)),
            if (widget.refreshFailed)
              Text(
                AppMessage.quoteDetailRefreshFailed.of(context),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            if (latest != null) ...[
              Text(
                latest.draft.title,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              Text(
                '${AppMessage.quoteRelatedInvoiceRevision.format(context, {'revision': latest.revision.toString()})} · ${quoteStatusMessage(latest.decision ?? (expired ? 'EXPIRED' : 'ISSUED')).of(context)}',
              ),
              if (data.today.isNotEmpty)
                Text(
                  AppMessage.quoteStatusAsOf.format(context, {
                    'date': data.today,
                    'timezone': data.timezone,
                  }),
                ),
              if (latest.customer.isNotEmpty) Text(latest.customer),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        AppMessage.quoteEstimatedTotalLabel.of(context),
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(
                        '${latest.draft.currency} ${latest.total}',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      Text(
                        '${AppMessage.invoiceSubtotal.of(context)} ${latest.subtotal} · ${AppMessage.invoiceTax.of(context)} ${latest.tax}',
                      ),
                      if (latest.draft.validUntil != null)
                        Text(
                          AppMessage.quoteValidUntilCalendar.format(context, {
                            'date': latest.draft.validUntil!,
                          }),
                        ),
                      Text(AppMessage.quoteEstimateNoPayment.of(context)),
                    ],
                  ),
                ),
              ),
              FilledButton.icon(
                onPressed: _pending ? null : () => _document(latest),
                icon: const Icon(Icons.picture_as_pdf_outlined),
                label: Text(AppMessage.quoteOpenIssuedQuote.of(context)),
              ),
              if (latest.decision == 'ACCEPTED')
                OutlinedButton.icon(
                  onPressed: _eligible ? () => _scope(latest) : null,
                  icon: const Icon(Icons.playlist_add_check),
                  label: Text(AppMessage.reviewAcceptedScope.of(context)),
                ),
              if (latest.decision == 'ACCEPTED')
                FilledButton.tonalIcon(
                  onPressed: _eligible
                      ? () => _scope(latest, createNewJob: true)
                      : null,
                  icon: const Icon(Icons.add_business_outlined),
                  label: Text(AppMessage.quoteCreateJob.of(context)),
                ),
              if (latest.decision == 'ACCEPTED')
                OutlinedButton.icon(
                  onPressed: _eligible ? () => _invoiceQuote(latest) : null,
                  icon: const Icon(Icons.receipt_long_outlined),
                  label: Text(AppMessage.invoiceActualWork.of(context)),
                ),
              OutlinedButton(
                onPressed: _pending
                    ? null
                    : () => Navigator.push<void>(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) =>
                              QuoteRelatedInvoicesScreen(quoteId: widget.id),
                        ),
                      ),
                child: Text(AppMessage.quoteRelatedInvoices.of(context)),
              ),
            ],
            const SizedBox(height: 16),
            if (draft != null || latest != null)
              OutlinedButton.icon(
                onPressed: _eligible ? _edit : null,
                icon: const Icon(Icons.edit_outlined),
                label: Text(
                  draft != null
                      ? AppMessage.quoteEditDraft.of(context)
                      : AppMessage.quoteCreateRevisedDraft.of(context),
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
                        AppMessage.quoteEditableDraft.of(context),
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      Text(
                        draft?.title ??
                            AppMessage.quoteIssuePending.of(context),
                      ),
                      Text(AppMessage.quoteIssueReviewGuidance.of(context)),
                      const SizedBox(height: 12),
                      OutlinedButton(
                        onPressed:
                            _eligible &&
                                draft != null &&
                                data.draftDigits != null
                            ? _reviewIssue
                            : null,
                        child: Text(AppMessage.quoteReviewIssue.of(context)),
                      ),
                      BusinessCommand(
                        key: ValueKey('issue/${widget.id}'),
                        label: latest == null
                            ? AppMessage.quoteIssueNow.of(context)
                            : AppMessage.quoteIssueNextRevision.of(context),
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
                            throw FormatException(
                              AppMessage.quoteReviewDraftBeforeIssue.of(
                                context,
                              ),
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
                        AppMessage.quoteAcceptanceRecordTitle.of(context),
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (latest.decision != null &&
                          _pendingKind != 'decision') ...[
                        Text(quoteStatusMessage(latest.decision!).of(context)),
                        if (latest.decisionNote?.isNotEmpty == true)
                          Text(latest.decisionNote!),
                        Text(
                          AppMessage.quoteDecisionRetainedNotice.of(context),
                        ),
                      ] else ...[
                        Text(AppMessage.quoteDecisionPrivacyNotice.of(context)),
                        const SizedBox(height: 12),
                        DropdownButtonFormField<String>(
                          initialValue: _decision,
                          decoration: InputDecoration(
                            labelText: AppMessage.quoteDecisionLabel.of(
                              context,
                            ),
                          ),
                          items: [
                            DropdownMenuItem(
                              value: 'ACCEPTED',
                              child: Text(
                                AppMessage.quoteStatusAccepted.of(context),
                              ),
                            ),
                            DropdownMenuItem(
                              value: 'DECLINED',
                              child: Text(
                                AppMessage.quoteStatusDeclined.of(context),
                              ),
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
                        Semantics(
                          identifier: 'quote-decision-note',
                          child: TextFormField(
                            controller: _note,
                            enabled: _eligible,
                            minLines: 2,
                            maxLines: 5,
                            maxLength: 2000,
                            maxLengthEnforcement: MaxLengthEnforcement.none,
                            decoration: InputDecoration(
                              labelText: AppMessage.quotePrivateDecisionLabel
                                  .of(context),
                              helperText: AppMessage.quotePrivateDecisionHelp
                                  .of(context),
                              errorText: _noteError,
                            ),
                            onChanged: (_) => setState(() {
                              _decisionReview = null;
                              _noteError = null;
                            }),
                          ),
                        ),
                        OutlinedButton(
                          onPressed: _eligible && latest.decision == null
                              ? _reviewDecision
                              : null,
                          child: Text(
                            AppMessage.quoteReviewCustomerDecision.of(context),
                          ),
                        ),
                        BusinessCommand(
                          key: ValueKey('decision/${widget.id}'),
                          label: AppMessage.quoteRecordReviewedDecision.of(
                            context,
                          ),
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
                              throw FormatException(
                                AppMessage.quoteReviewCurrentDecisionRequired
                                    .of(context),
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
                AppMessage.quoteRevisionHistoryTitle.of(context),
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
                      child: Text(
                        AppMessage.quoteRetryRevisionHistory.of(context),
                      ),
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
                          title: Text(
                            AppMessage.quoteRevisionTitle.format(context, {
                              'revision': entry.revision.toString(),
                            }),
                          ),
                          subtitle: Text(
                            AppMessage.quoteHistoryEntry.format(context, {
                              'decision': entry.decision == null
                                  ? AppMessage.quoteNoDecisionRecorded.of(
                                      context,
                                    )
                                  : quoteStatusMessage(entry.decision!)
                                        .of(context),
                              'timestamp': DateTime.fromMillisecondsSinceEpoch(
                                entry.created,
                                isUtc: true,
                              ).toIso8601String(),
                            }),
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
                          child: Text(
                            AppMessage.quoteLoadOlderRevisions.of(context),
                          ),
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
