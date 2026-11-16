import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../history_api.dart';
import 'history_time_format.dart';

class HistoryRevisionsScreen extends ConsumerStatefulWidget {
  const HistoryRevisionsScreen({
    super.key,
    required this.jobId,
    required this.sessionId,
  });
  final String jobId, sessionId;
  @override
  ConsumerState<HistoryRevisionsScreen> createState() =>
      _HistoryRevisionsScreenState();
}

class _HistoryRevisionsScreenState
    extends ConsumerState<HistoryRevisionsScreen> {
  late Future<List<HistoryRevision>> _data = ref
      .read(historyApiProvider)
      .revisions(widget.jobId, widget.sessionId);
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Entry changes')),
    body: FutureBuilder(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: TextButton(
              onPressed: () => setState(
                () => _data = ref
                    .read(historyApiProvider)
                    .revisions(widget.jobId, widget.sessionId),
              ),
              child: const Text('Retry entry changes'),
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const Text(
              'Corrections and deletions keep their local revision history. Prepared invoices and exported copies keep the values captured when they were created.',
            ),
            if (snapshot.data!.isEmpty)
              const Text('No retained changes for this entry.'),
            for (final revision in snapshot.data!)
              HistoryRevisionTile(revision: revision),
          ],
        );
      },
    ),
  );
}

class HistoryRevisionTile extends StatelessWidget {
  const HistoryRevisionTile({required this.revision, this.onTap});
  final HistoryRevision revision;
  final VoidCallback? onTap;
  String _summary() {
    try {
      final data = jsonDecode(revision.payload ?? '{}') as Map<String, dynamic>;
      final start = data['start_ms'], end = data['end_ms'];
      return start is int
          ? '${historyUtc(start)}\n${end is int ? historyUtc(end) : 'Running when saved'}\n${data['timezone'] ?? ''}'
          : revision.deleted
          ? 'Work entry removed'
          : 'No retained time details';
    } catch (_) {
      return 'Saved change details unavailable';
    }
  }

  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      leading: Icon(
        revision.deleted ? Icons.delete_outline : Icons.edit_outlined,
      ),
      title: Text(revision.origin.replaceAll('_', ' ').toLowerCase()),
      subtitle: Text('${historyUtc(revision.changed)}\n${_summary()}'),
      trailing: onTap == null ? null : const Icon(Icons.chevron_right),
      onTap: onTap,
    ),
  );
}
