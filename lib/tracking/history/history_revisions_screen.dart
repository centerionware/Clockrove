import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/app_localizations.dart';
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
    appBar: AppBar(title: Text(AppMessage.historyEntryChangesTitle.of(context))),
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
              child: Text(AppMessage.historyRetryEntryChanges.of(context)),
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(AppMessage.historyRevisionDisclosure.of(context)),
            if (snapshot.data!.isEmpty)
              Text(AppMessage.historyNoRetainedChanges.of(context)),
            for (final revision in snapshot.data!)
              HistoryRevisionTile(revision: revision),
          ],
        );
      },
    ),
  );
}

class HistoryRevisionTile extends StatelessWidget {
  const HistoryRevisionTile({super.key, required this.revision, this.onTap});
  final HistoryRevision revision;
  final VoidCallback? onTap;
  String _summary(BuildContext context) {
    try {
      final data = jsonDecode(revision.payload ?? '{}') as Map<String, dynamic>;
      final start = data['start_ms'], end = data['end_ms'];
      if (start is int) {
        final endLabel = end is int
            ? historyUtc(context, end)
            : AppMessage.historyRunningWhenSaved.of(context);
        return '${historyUtc(context, start)}\n$endLabel\n'
            '${data['timezone'] ?? ''}';
      }
      return revision.deleted
          ? AppMessage.historyWorkEntryRemoved.of(context)
          : AppMessage.historyNoTimeDetails.of(context);
    } catch (_) {
      return AppMessage.historySavedDetailsUnavailable.of(context);
    }
  }

  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      leading: Icon(
        revision.deleted ? Icons.delete_outline : Icons.edit_outlined,
      ),
      title: Text(AppMessage.historySavedChange.of(context)),
      subtitle: Text(
        '${historyUtc(context, revision.changed)}\n${_summary(context)}',
      ),
      trailing: onTap == null ? null : const Icon(Icons.chevron_right),
      onTap: onTap,
    ),
  );
}
