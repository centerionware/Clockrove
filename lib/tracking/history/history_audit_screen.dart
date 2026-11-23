import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/app_localizations.dart';
import '../history_api.dart';
import 'history_revisions_screen.dart';

class HistoryAuditScreen extends ConsumerStatefulWidget {
  const HistoryAuditScreen({super.key, required this.jobId});
  final String jobId;
  @override
  ConsumerState<HistoryAuditScreen> createState() => _HistoryAuditScreenState();
}

class _HistoryAuditScreenState extends ConsumerState<HistoryAuditScreen> {
  final _events = <HistoryEvent>[];
  HistoryAudit? _page;
  bool _busy = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool more = false}) async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final page = await ref
          .read(historyApiProvider)
          .audit(widget.jobId, before: more ? _page?.next : null);
      if (!mounted) {
        return;
      }
      setState(() {
        if (more && _page?.version != page.version) {
          _error = AppMessage.historyLoadChanged.of(context);
        } else {
          if (!more) {
            _events.clear();
          }
          _events.addAll(page.events);
          _page = page;
        }
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error = AppMessage.historyLoadFailed.of(context));
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(AppMessage.historyAuditTitle.of(context)),
      actions: [
        IconButton(
          tooltip: AppMessage.historyRefresh.of(context),
          onPressed: _busy ? null : () => _load(),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Text(AppMessage.historyAuditDisclosure.of(context)),
        if (_busy) const LinearProgressIndicator(),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_page != null && _events.isEmpty)
          Padding(
            padding: const EdgeInsets.all(24),
            child: Text(AppMessage.historyNoChanges.of(context)),
          ),
        for (final event in _events)
          HistoryRevisionTile(
            revision: event.revision,
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => HistoryRevisionsScreen(
                  jobId: widget.jobId,
                  sessionId: event.sessionId,
                ),
              ),
            ),
          ),
        if (_page?.next != null)
          OutlinedButton(
            onPressed: _busy ? null : () => _load(more: true),
            child: Text(AppMessage.historyLoadOlder.of(context)),
          ),
      ],
    ),
  );
}
