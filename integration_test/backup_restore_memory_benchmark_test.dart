import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_import_api.dart';
import 'package:clockrove/backup/backup_import_stream.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

const _password = 'synthetic-device-benchmark-passphrase';
const _rowCount = 601;
const _mediaBytes = 1024 * 1024;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('64 MiB authenticated restore remains within the RSS budget', (
    tester,
  ) async {
    await tester.pumpWidget(const SizedBox.shrink());
    final result = await tester.runAsync(() async {
      final envelopeFile = File(
        '${Directory.systemTemp.path}/clockrove_restore_fixture.clockrove',
      );
      expect(await envelopeFile.exists(), isTrue);
      final envelopeBytes = await envelopeFile.length();
      expect(envelopeBytes, BackupCryptoV2.maxEnvelopeBytes);

      final api = BackupImportApi();
      final rss = _RssSampler();
      await rss.start();
      final restoreClock = Stopwatch()..start();
      late BackupArchiveImportHeader importHeader;
      late List<int> expectedDigest;
      late int maximumParserBufferBytes;
      try {
          final firstPass = BackupArchiveHeaderProbe();
          final authenticated = await BackupCryptoV2.decrypt(
            password: _password,
            envelope: envelopeFile.openRead(),
            privateArchive: firstPass,
          );
          expect(
            authenticated.archiveBytes,
            BackupArchiveCodec.maxArchiveBytes,
          );
          expect(firstPass.archiveBytes, BackupArchiveCodec.maxArchiveBytes);
          importHeader = BackupArchiveImportHeader.parse(
            firstPass.headerBytes,
            authenticatedArchiveBytes: authenticated.archiveBytes,
          );
          expectedDigest = authenticated.archiveSha256;
          expect(importHeader.recordCount, _rowCount);
          expect(importHeader.mediaCount, 1);
          expect(importHeader.mediaBytes, _mediaBytes);

          final started = await api.begin(
            archiveFormatVersion: importHeader.formatVersion,
            schemaVersion: importHeader.schemaVersion,
            archiveDigest: _hex(authenticated.archiveSha256),
            recordCount: importHeader.recordCount,
            mediaCount: importHeader.mediaCount,
            mediaBytes: importHeader.mediaBytes,
          );
          final sink = BackupArchiveImportSink(
            api: api,
            importId: started.importId,
            header: importHeader,
            expectedArchiveSha256: authenticated.archiveSha256,
          );
          final restored = await BackupCryptoV2.decrypt(
            password: _password,
            envelope: envelopeFile.openRead(),
            privateArchive: sink,
          );
          expect(restored.archiveBytes, authenticated.archiveBytes);
          expect(restored.archiveSha256, authenticated.archiveSha256);
          expect(sink.rowsStaged, _rowCount);
          expect(
            sink.maximumBufferedArchiveBytes,
            lessThanOrEqualTo(
              BackupArchiveCodec.maxRecordBytes + BackupCryptoV2.chunkBytes,
            ),
          );
          maximumParserBufferBytes = sink.maximumBufferedArchiveBytes;

          final preview = await sink.seal();
          expect(preview.recordCount, _rowCount);
          expect(preview.addedCount, _rowCount);
          expect(preview.conflictCount, 0);
          expect(preview.applyReady, isTrue);
          final applied = await api.apply(
            importId: started.importId,
            previewToken: preview.previewToken,
            expectedLocalStateVersion: preview.localStateVersion,
          );
          expect(applied.insertedCount, _rowCount);
          await sink.discard();
          expect(await api.close(started.importId), isFalse);
      } finally {
        await rss.stop();
        restoreClock.stop();
      }

        expect(rss.sampleCount, greaterThanOrEqualTo(2));
        expect(
          rss.peakDeltaKiB,
          lessThanOrEqualTo(64 * 1024),
          reason: 'The complete authenticated restore must stay under 64 MiB.',
        );
        expect(
          rss.peakDeltaHwmKiB,
          lessThanOrEqualTo(64 * 1024),
          reason: 'Restore high-water RSS must stay under 64 MiB.',
        );
        // ignore: avoid_print
        print(
          'RESTORE_MEMORY_BENCHMARK '
          'archive_bytes=${BackupArchiveCodec.maxArchiveBytes} '
          'envelope_bytes=${BackupCryptoV2.maxEnvelopeBytes} '
          'record_count=$_rowCount samples=${rss.sampleCount} '
          'max_parser_buffer_bytes=$maximumParserBufferBytes '
          'elapsed_ms=${restoreClock.elapsedMilliseconds} '
          'sample_period_ms=20 baseline_rss_kib=${rss.baselineKiB} '
          'baseline_hwm_kib=${rss.baselineHwmKiB} '
          'peak_rss_kib=${rss.peakKiB} '
          'peak_delta_rss_kib=${rss.peakDeltaKiB} '
          'after_hwm_kib=${rss.afterHwmKiB} '
          'peak_delta_hwm_kib=${rss.peakDeltaHwmKiB}',
        );

        final cancelledStart = await api.begin(
          archiveFormatVersion: importHeader.formatVersion,
          schemaVersion: importHeader.schemaVersion,
          archiveDigest: _hex(expectedDigest),
          recordCount: importHeader.recordCount,
          mediaCount: importHeader.mediaCount,
          mediaBytes: importHeader.mediaBytes,
        );
        final cancelledSink = BackupArchiveImportSink(
          api: api,
          importId: cancelledStart.importId,
          header: importHeader,
          expectedArchiveSha256: expectedDigest,
        );
        final cancellation = BackupCryptoCancellationToken()..cancel();
        await expectLater(
          BackupCryptoV2.decrypt(
            password: _password,
            envelope: envelopeFile.openRead(),
            privateArchive: cancelledSink,
            cancellationToken: cancellation,
          ),
          throwsA(isA<BackupCryptoV2CancelledException>()),
        );
        expect(await api.close(cancelledStart.importId), isFalse);

        final corruptedStart = await api.begin(
          archiveFormatVersion: importHeader.formatVersion,
          schemaVersion: importHeader.schemaVersion,
          archiveDigest: _hex(expectedDigest),
          recordCount: importHeader.recordCount,
          mediaCount: importHeader.mediaCount,
          mediaBytes: importHeader.mediaBytes,
        );
        final corruptedSink = BackupArchiveImportSink(
          api: api,
          importId: corruptedStart.importId,
          header: importHeader,
          expectedArchiveSha256: expectedDigest,
        );
        await expectLater(
          BackupCryptoV2.decrypt(
            password: _password,
            envelope: _corruptLastByte(
              envelopeFile.openRead(),
              expectedLength: envelopeBytes,
            ),
            privateArchive: corruptedSink,
          ),
          throwsA(isA<FormatException>()),
        );
        expect(await api.close(corruptedStart.importId), isFalse);

      return (
        archiveBytes: BackupArchiveCodec.maxArchiveBytes,
        envelopeBytes: BackupCryptoV2.maxEnvelopeBytes,
        baselineKiB: rss.baselineKiB,
        peakKiB: rss.peakKiB,
        peakDeltaKiB: rss.peakDeltaKiB,
        baselineHwmKiB: rss.baselineHwmKiB,
        afterHwmKiB: rss.afterHwmKiB,
        peakDeltaHwmKiB: rss.peakDeltaHwmKiB,
        elapsedMs: restoreClock.elapsedMilliseconds,
        samples: rss.sampleCount,
        maxParserBufferBytes: maximumParserBufferBytes,
      );
    });

    expect(result, isNotNull);
    // ignore: avoid_print
    print(
      'RESTORE_MEMORY_BENCHMARK_COMPLETE '
      'archive_bytes=${result!.archiveBytes} '
      'envelope_bytes=${result.envelopeBytes} '
      'samples=${result.samples} elapsed_ms=${result.elapsedMs} '
      'max_parser_buffer_bytes=${result.maxParserBufferBytes} '
      'baseline_rss_kib=${result.baselineKiB} '
      'peak_rss_kib=${result.peakKiB} '
      'peak_delta_rss_kib=${result.peakDeltaKiB} '
      'baseline_hwm_kib=${result.baselineHwmKiB} '
      'after_hwm_kib=${result.afterHwmKiB} '
      'peak_delta_hwm_kib=${result.peakDeltaHwmKiB}',
    );
  });
}

Stream<List<int>> _corruptLastByte(
  Stream<List<int>> source, {
  required int expectedLength,
}) async* {
  var received = 0;
  await for (final bytes in source) {
    received += bytes.length;
    if (received == expectedLength) {
      final corrupted = Uint8List.fromList(bytes);
      corrupted[corrupted.length - 1] ^= 0xff;
      yield corrupted;
    } else {
      yield bytes;
    }
  }
  if (received != expectedLength) {
    throw const FormatException(
      'The synthetic encrypted fixture was truncated.',
    );
  }
}

String _hex(List<int> bytes) =>
    bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();

final class _RssSampler {
  Timer? _timer;
  bool _sampleInFlight = false;
  int _baselineKiB = 0;
  int _baselineHwmKiB = 0;
  int _peakKiB = 0;
  int _afterHwmKiB = 0;
  int _sampleCount = 0;

  int get baselineKiB => _baselineKiB;
  int get baselineHwmKiB => _baselineHwmKiB;
  int get peakKiB => _peakKiB;
  int get afterHwmKiB => _afterHwmKiB;
  int get peakDeltaHwmKiB => _afterHwmKiB > _baselineHwmKiB
      ? _afterHwmKiB - _baselineHwmKiB
      : 0;
  int get sampleCount => _sampleCount;
  int get peakDeltaKiB => _peakKiB > _baselineKiB
      ? _peakKiB - _baselineKiB
      : 0;

  Future<void> start() async {
    final baseline = await _processMemoryKiB();
    _baselineKiB = baseline.rss;
    _baselineHwmKiB = baseline.hwm;
    _peakKiB = _baselineKiB;
    _timer = Timer.periodic(const Duration(milliseconds: 20), (_) {
      if (_sampleInFlight) return;
      _sampleInFlight = true;
      unawaited(_sample().whenComplete(() => _sampleInFlight = false));
    });
  }

  Future<void> stop() async {
    _timer?.cancel();
    _timer = null;
    while (_sampleInFlight) {
      await Future<void>.delayed(const Duration(milliseconds: 1));
    }
    await _sample();
    _afterHwmKiB = (await _processMemoryKiB()).hwm;
  }

  Future<void> _sample() async {
    final memory = await _processMemoryKiB();
    if (memory.rss > _peakKiB) _peakKiB = memory.rss;
    if (memory.hwm > _afterHwmKiB) _afterHwmKiB = memory.hwm;
    _sampleCount++;
  }
}

Future<({int rss, int hwm})> _processMemoryKiB() async {
  final lines = await File('/proc/self/status').readAsLines();
  int read(String name) {
    final line = lines.firstWhere(
      (entry) => entry.startsWith('$name:'),
      orElse: () => throw FormatException('Android did not report $name.'),
    );
    final match = RegExp('^$name:\\s+(\\d+)\\s+kB$').firstMatch(line);
    if (match == null) throw FormatException('Android reported invalid $name.');
    return int.parse(match.group(1)!);
  }

  return (rss: read('VmRSS'), hwm: read('VmHWM'));
}
