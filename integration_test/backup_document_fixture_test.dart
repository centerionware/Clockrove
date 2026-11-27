import 'dart:io';
import 'dart:typed_data';

import 'package:clockrove/automation/automation_api.dart';
import 'package:clockrove/backup/backup_snapshot_exporter.dart';
import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/tracking/history_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

const _jobId = 'ci-document-source';
const _jobName = 'Synthetic restore source';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('export synthetic document picker fixture', (tester) async {
    await tester.runAsync(() async {
      final timer = TimerApi();
      final before = await timer.snapshot();
      expect(before.jobs, isEmpty);
      expect(before.running, isNull);
      final created = await timer.createJob(
        id: newBusinessId(),
        version: before.version,
        jobId: _jobId,
        name: _jobName,
      );
      expect(created.committed, isTrue);

      final entry = await HistoryApi().create(
        newBusinessId(),
        created.version,
        const HistoryDraft(
          sessionId: 'ci-document-session',
          jobId: _jobId,
          start: 1735689600000,
          end: 1735693200000,
          timezone: 'Etc/UTC',
          offsetSeconds: 0,
        ),
      );
      expect(entry.committed, isTrue);

      const automation = AutomationApi();
      final site = await automation.saveSite(
        newAutomationId(),
        entry.version,
        _jobId,
        const WorkSite('ci-document-site', 'Synthetic site', 10, 20, 150, true),
      );
      expect(site.committed, isTrue);
      final policy = await automation.saveSettings(
        newAutomationId(),
        site.version,
        _jobId,
        const SiteSettings(enabled: true, autoStart: true),
      );
      expect(policy.committed, isTrue);

      final backup = await BackupSnapshotExporter().exportStream();
      final file = File(
        '${Directory.systemTemp.path}/clockrove-picker-fixture.clockrove',
      );
      try {
        final sink = _SyntheticFixtureSink(file);
        await for (final chunk in backup.archiveStream) {
          await sink.write(chunk);
        }
        await sink.commit();
        // ignore: avoid_print
        print('CLOCKROVE_DOCUMENT_FIXTURE path=${file.path}');
      } finally {
        await backup.close();
      }
    });
  });
}

final class _SyntheticFixtureSink implements BackupTransactionalByteSink {
  _SyntheticFixtureSink(this.file);

  final File file;
  final _bytes = BytesBuilder(copy: true);

  @override
  Future<void> write(List<int> bytes) async {
    if (_bytes.length + bytes.length > 1024 * 1024) {
      throw StateError('Synthetic document fixture exceeded 1 MiB.');
    }
    _bytes.add(bytes);
  }

  @override
  Future<void> commit() async {
    await file.writeAsBytes(_bytes.takeBytes(), flush: true);
  }

  @override
  Future<void> discard() async {
    _bytes.clear();
    if (await file.exists()) await file.delete();
  }
}
