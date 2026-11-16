import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
          _error = 'Work changed. Refresh change history before loading more.';
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
        setState(
          () => _error =
              'Could not load change history. Retry to read saved changes.',
        );
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
      title: const Text('Change history'),
      actions: [
        IconButton(
          tooltip: 'Refresh changes',
          onPressed: _busy ? null : () => _load(),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const Text(
          'Includes deleted work entries. Changes are ordered by when they were saved locally. This history is an audit aid, not tamper-proof evidence.',
        ),
        if (_busy) const LinearProgressIndicator(),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_page != null && _events.isEmpty)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Text('No saved changes yet.'),
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
            child: const Text('Load older changes'),
          ),
      ],
    ),
  );
}
