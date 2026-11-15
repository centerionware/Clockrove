import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_snapshot_exporter.dart';
import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';
import 'package:integration_test/integration_test.dart';

const _targetMediaBytes = 55 * 1024 * 1024;
const _mediaToleranceBytes = 1024 * 1024;
const _maximumTaskImageBytes = 5_000_000;
const _fixtureJobId = 'ci-backup-memory-job';
const _fixtureTaskId = 'ci-backup-memory-task';
const _fixtureImageIdPrefix = 'ci-backup-memory-image-';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('seed synthetic task media for full-path memory benchmark', (
    tester,
  ) async {
    await tester.pumpWidget(const SizedBox.shrink());
    final metrics = await tester.runAsync(_seedSyntheticTaskMedia);
    expect(metrics, isNotNull);
    final result = metrics!;
    // ignore: avoid_print
    print(
      'BACKUP_SNAPSHOT_MEMORY_SEED '
      'pid=${result.pid} process_start_ticks=${result.processStartTicks} '
      'attachment_count=${result.attachmentCount} '
      'media_bytes=${result.mediaBytes} target_bytes=$_targetMediaBytes',
    );
  });

  testWidgets('measure full-path backup exporter and encryption memory', (
    tester,
  ) async {
    await tester.pumpWidget(const SizedBox.shrink());
    final metrics = await tester.runAsync(_measureFullBackupPath);
    expect(metrics, isNotNull);
    final result = metrics!;
    // ignore: avoid_print
    print(
      'BACKUP_SNAPSHOT_MEMORY_BENCHMARK '
      'pid=${result.pid} process_start_ticks=${result.processStartTicks} '
      'seed_attachment_count=${result.seedAttachmentCount} '
      'seed_media_bytes=${result.seedMediaBytes} '
      'snapshot_media_count=${result.snapshotMediaCount} '
      'snapshot_media_bytes=${result.snapshotMediaBytes} '
      'archive_bytes=${result.archiveBytes} '
      'envelope_bytes=${result.envelopeBytes} '
      'baseline_rss_kib=${result.baselineRssKiB} '
      'after_export_rss_kib=${result.afterExportRssKiB} '
      'after_encrypt_rss_kib=${result.afterEncryptRssKiB} '
      'full_path_peak_rss_kib=${result.peakRssKiB} '
      'full_path_peak_delta_rss_kib=${result.peakDeltaRssKiB} '
      'export_ms=${result.exportMs} encrypt_ms=${result.encryptMs}',
    );
  });
}

Future<_SeedMetrics> _seedSyntheticTaskMedia() async {
  final timerApi = TimerApi();
  final timer = await timerApi.snapshot();
  const jobId = _fixtureJobId;
  if (!timer.jobs.any((job) => job.id == jobId)) {
    final jobOutcome = await timerApi.createJob(
      id: newBusinessId(),
      version: timer.version,
      jobId: jobId,
      name: 'CI synthetic backup memory fixture',
    );
    if (!jobOutcome.committed) {
      throw StateError('Could not create the synthetic benchmark job.');
    }
  }

  final businessApi = BusinessApi();
  final initialBoard = await businessApi.board(jobId);
  final column = initialBoard.columns.firstWhere(
    (candidate) => !candidate.completed,
    orElse: () => throw StateError('The synthetic job has no open column.'),
  );
  const taskId = _fixtureTaskId;
  if (!initialBoard.cards.any((card) => card.id == taskId)) {
    final cardOutcome = await businessApi.saveCard(
      newBusinessId(),
      initialBoard,
      TaskCard(
        id: taskId,
        columnId: column.id,
        title: 'Synthetic media for backup memory measurement',
        description: 'Generated CI-only PNG fixtures.',
        position: 0,
        billable: false,
        currency: initialBoard.currency,
        chargeType: 'UNCLASSIFIED',
      ),
    );
    if (!cardOutcome.committed) {
      throw StateError('Could not create the synthetic benchmark task.');
    }
  }

  final fullImage = await _noisePng(1024, 1024, 0x43524f56);
  if (fullImage.length > _maximumTaskImageBytes) {
    throw StateError('A generated PNG exceeded the native image size limit.');
  }

  var conversation = await businessApi.taskConversation(jobId, taskId);
  var attachmentCount = conversation.taskAttachments.length;
  var mediaBytes = conversation.taskAttachments.fold<int>(
    0,
    (total, attachment) => total + attachment.bytes,
  );
  if (attachmentCount > 0 &&
      attachmentCount <= 20 &&
      (mediaBytes - _targetMediaBytes).abs() <= _mediaToleranceBytes) {
    return _SeedMetrics(
      pid: pid,
      processStartTicks: await _processStartTicks(),
      attachmentCount: attachmentCount,
      mediaBytes: mediaBytes,
    );
  }
  for (final attachment in List.of(conversation.taskAttachments)) {
    final outcome = await businessApi.removeTaskImage(
      newBusinessId(),
      conversation,
      attachment.id,
    );
    if (!outcome.committed) {
      throw StateError('Could not reset a stale benchmark attachment.');
    }
    conversation = await businessApi.taskConversation(jobId, taskId);
  }
  attachmentCount = 0;
  mediaBytes = 0;

  Future<void> attach(Uint8List bytes) async {
    if (attachmentCount >= 20) {
      throw StateError('The synthetic task reached its attachment limit.');
    }
    conversation = await businessApi.taskConversation(jobId, taskId);
    final attachmentId = '$_fixtureImageIdPrefix$attachmentCount';
    final outcome = await businessApi.attachTaskImage(
      newBusinessId(),
      conversation,
      attachmentId,
      null,
      bytes,
    );
    if (!outcome.committed) {
      throw StateError('Native task image write failed: ${outcome.conflict}');
    }
    mediaBytes += bytes.length;
    attachmentCount++;
  }

  while (mediaBytes + fullImage.length <= _targetMediaBytes) {
    await attach(fullImage);
  }

  final remainingBytes = _targetMediaBytes - mediaBytes;
  if (remainingBytes > 0) {
    var width = (1024 * remainingBytes ~/ fullImage.length)
        .clamp(1, 1024)
        .toInt();
    Uint8List partialImage = Uint8List(0);
    for (var attempt = 0; attempt < 8; attempt++) {
      partialImage = await _noisePng(width, 1024, 0x43524f56 + attempt + 1);
      if (partialImage.length <= remainingBytes) break;
      if (width <= 1) {
        throw StateError('Could not fit the final synthetic PNG in target.');
      }
      final scaledWidth = width * remainingBytes ~/ partialImage.length;
      width = scaledWidth.clamp(1, width - 1).toInt();
    }
    if (partialImage.isEmpty || partialImage.length > remainingBytes) {
      throw StateError('Could not fit the final synthetic PNG in target.');
    }
    await attach(partialImage);
  }

  if ((mediaBytes - _targetMediaBytes).abs() > _mediaToleranceBytes ||
      attachmentCount > 20) {
    throw StateError(
      'Synthetic media was outside the 55 MiB target: '
      '$mediaBytes bytes in $attachmentCount attachments.',
    );
  }

  conversation = await businessApi.taskConversation(jobId, taskId);
  final storedMediaBytes = conversation.taskAttachments.fold<int>(
    0,
    (total, attachment) => total + attachment.bytes,
  );
  if (conversation.taskAttachments.length != attachmentCount ||
      storedMediaBytes != mediaBytes) {
    throw StateError('Native task image metadata did not match the seed.');
  }

  return _SeedMetrics(
    pid: pid,
    processStartTicks: await _processStartTicks(),
    attachmentCount: attachmentCount,
    mediaBytes: mediaBytes,
  );
}

Future<_BenchmarkMetrics> _measureFullBackupPath() async {
  const jobId = _fixtureJobId;
  const taskId = _fixtureTaskId;

  late final TaskConversation conversation;
  try {
    conversation = await const BusinessApi().taskConversation(jobId, taskId);
  } catch (error) {
    throw StateError(
      'Fresh-process benchmark could not query persisted fixture: $error',
    );
  }
  final storedMediaBytes = conversation.taskAttachments.fold<int>(
    0,
    (total, attachment) => total + attachment.bytes,
  );
  final seedAttachmentCount = conversation.taskAttachments.length;
  final seedMediaBytes = storedMediaBytes;
  if (seedAttachmentCount == 0 ||
      (seedMediaBytes - _targetMediaBytes).abs() > _mediaToleranceBytes) {
    throw StateError('Fresh-process benchmark could not verify seeded media.');
  }

  final baselineRssKiB = await _processHighWaterRssKiB();
  final exportClock = Stopwatch()..start();
  final exported = await BackupSnapshotExporter().export();
  exportClock.stop();
  final afterExportRssKiB = await _processHighWaterRssKiB();
  if (exported.mediaBytes < seedMediaBytes ||
      exported.mediaCount < seedAttachmentCount ||
      exported.archiveBytes.length > BackupCryptoV2.maxArchiveBytes) {
    throw StateError(
      'Snapshot export omitted seeded media or exceeded limits.',
    );
  }

  final outputDirectory = await Directory.systemTemp.createTemp(
    'clockrove-full-backup-memory-',
  );
  final envelopeFile = File('${outputDirectory.path}/backup.clockrove');
  try {
    final encryptClock = Stopwatch()..start();
    final encrypted = await BackupCryptoV2.encrypt(
      password: 'synthetic-full-path-memory-benchmark-passphrase',
      archive: _archiveChunks(exported.archiveBytes),
      destination: _FileTransactionalSink(envelopeFile),
    );
    encryptClock.stop();
    final afterEncryptRssKiB = await _processHighWaterRssKiB();
    if (encrypted.archiveBytes != exported.archiveBytes.length ||
        await envelopeFile.length() != encrypted.envelopeBytes) {
      throw StateError('Encrypted snapshot length did not match its result.');
    }

    final peakRssKiB = afterExportRssKiB > afterEncryptRssKiB
        ? afterExportRssKiB
        : afterEncryptRssKiB;
    return _BenchmarkMetrics(
      pid: pid,
      processStartTicks: await _processStartTicks(),
      seedAttachmentCount: seedAttachmentCount,
      seedMediaBytes: seedMediaBytes,
      snapshotMediaCount: exported.mediaCount,
      snapshotMediaBytes: exported.mediaBytes,
      archiveBytes: encrypted.archiveBytes,
      envelopeBytes: encrypted.envelopeBytes,
      baselineRssKiB: baselineRssKiB,
      afterExportRssKiB: afterExportRssKiB,
      afterEncryptRssKiB: afterEncryptRssKiB,
      peakRssKiB: peakRssKiB,
      peakDeltaRssKiB: peakRssKiB > baselineRssKiB
          ? peakRssKiB - baselineRssKiB
          : 0,
      exportMs: exportClock.elapsedMilliseconds,
      encryptMs: encryptClock.elapsedMilliseconds,
    );
  } finally {
    if (await envelopeFile.exists()) await envelopeFile.delete();
    if (await outputDirectory.exists()) {
      await outputDirectory.delete(recursive: true);
    }
  }
}

Future<Uint8List> _noisePng(int width, int height, int seed) async {
  final pixels = Uint8List(width * height * 4);
  var state = seed;
  for (var offset = 0; offset < pixels.length; offset += 4) {
    state = (state ^ (state << 13)) & 0xffffffff;
    state ^= state >> 17;
    state = (state ^ (state << 5)) & 0xffffffff;
    pixels[offset] = state & 0xff;
    state = (state ^ (state << 13)) & 0xffffffff;
    state ^= state >> 17;
    state = (state ^ (state << 5)) & 0xffffffff;
    pixels[offset + 1] = state & 0xff;
    state = (state ^ (state << 13)) & 0xffffffff;
    state ^= state >> 17;
    state = (state ^ (state << 5)) & 0xffffffff;
    pixels[offset + 2] = state & 0xff;
    pixels[offset + 3] = 0xff;
  }

  final buffer = await ui.ImmutableBuffer.fromUint8List(pixels);
  try {
    final descriptor = ui.ImageDescriptor.raw(
      buffer,
      width: width,
      height: height,
      pixelFormat: ui.PixelFormat.rgba8888,
    );
    try {
      final codec = await descriptor.instantiateCodec();
      try {
        final image = (await codec.getNextFrame()).image;
        try {
          final png = await image.toByteData(format: ui.ImageByteFormat.png);
          if (png == null) throw StateError('Could not encode synthetic PNG.');
          return Uint8List.fromList(
            png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes),
          );
        } finally {
          image.dispose();
        }
      } finally {
        codec.dispose();
      }
    } finally {
      descriptor.dispose();
    }
  } finally {
    buffer.dispose();
  }
}

Stream<List<int>> _archiveChunks(Uint8List archive) async* {
  var offset = 0;
  while (offset < archive.length) {
    final nextOffset = offset + BackupCryptoV2.chunkBytes;
    final end = nextOffset < archive.length ? nextOffset : archive.length;
    yield Uint8List.sublistView(archive, offset, end);
    offset = end;
  }
}

Future<int> _processHighWaterRssKiB() async {
  final status = await File('/proc/self/status').readAsLines();
  final line = status.firstWhere(
    (entry) => entry.startsWith('VmHWM:'),
    orElse: () => throw const FormatException(
      'Android did not report process high-water RSS.',
    ),
  );
  final match = RegExp(r'^VmHWM:\s+(\d+)\s+kB$').firstMatch(line);
  if (match == null) {
    throw const FormatException('Android reported an invalid process RSS.');
  }
  return int.parse(match.group(1)!);
}

Future<String> _processStartTicks() async {
  final stat = await File('/proc/self/stat').readAsString();
  final closeParen = stat.lastIndexOf(')');
  if (closeParen < 0) throw const FormatException('Invalid process stat.');
  final fields = stat.substring(closeParen + 1).trim().split(RegExp(r'\s+'));
  if (fields.length <= 19) {
    throw const FormatException('Process start time is missing.');
  }
  return fields[19];
}

final class _SeedMetrics {
  const _SeedMetrics({
    required this.pid,
    required this.processStartTicks,
    required this.attachmentCount,
    required this.mediaBytes,
  });

  final int pid;
  final String processStartTicks;
  final int attachmentCount;
  final int mediaBytes;
}

final class _BenchmarkMetrics {
  const _BenchmarkMetrics({
    required this.pid,
    required this.processStartTicks,
    required this.seedAttachmentCount,
    required this.seedMediaBytes,
    required this.snapshotMediaCount,
    required this.snapshotMediaBytes,
    required this.archiveBytes,
    required this.envelopeBytes,
    required this.baselineRssKiB,
    required this.afterExportRssKiB,
    required this.afterEncryptRssKiB,
    required this.peakRssKiB,
    required this.peakDeltaRssKiB,
    required this.exportMs,
    required this.encryptMs,
  });

  final int pid;
  final String processStartTicks;
  final int seedAttachmentCount;
  final int seedMediaBytes;
  final int snapshotMediaCount;
  final int snapshotMediaBytes;
  final int archiveBytes;
  final int envelopeBytes;
  final int baselineRssKiB;
  final int afterExportRssKiB;
  final int afterEncryptRssKiB;
  final int peakRssKiB;
  final int peakDeltaRssKiB;
  final int exportMs;
  final int encryptMs;
}

final class _FileTransactionalSink implements BackupTransactionalByteSink {
  _FileTransactionalSink(this.file);

  final File file;
  RandomAccessFile? _handle;
  bool _committed = false;

  Future<RandomAccessFile> get _open async =>
      _handle ??= await file.open(mode: FileMode.write);

  @override
  Future<void> write(List<int> bytes) async {
    if (_committed) throw StateError('Cannot write after commit.');
    await (await _open).writeFrom(bytes);
  }

  @override
  Future<void> commit() async {
    final handle = await _open;
    await handle.flush();
    await handle.close();
    _handle = null;
    _committed = true;
  }

  @override
  Future<void> discard() async {
    final handle = _handle;
    _handle = null;
    if (handle != null) await handle.close();
    if (await file.exists()) await file.delete();
  }
}
