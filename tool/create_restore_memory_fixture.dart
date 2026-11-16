import 'dart:io';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';

const _password = 'synthetic-device-benchmark-passphrase';
const _customerCount = 600;
const _recordCount = _customerCount + 1;
const _mediaBytes = 1024 * 1024;
const _mediaId = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

Future<void> main(List<String> arguments) async {
  if (arguments.length != 1) {
    throw ArgumentError('Expected one output path for the encrypted fixture.');
  }
  final output = File(arguments.single);
  final notes = _exactNoteLengths();
  final plan = BackupArchiveCodec.prepareStream(
    schemaVersion: BackupArchiveCodec.latestSchemaVersion,
    stateVersion: 0,
    capturedAtUtcMillis: 0,
    rows: _fixtureRows(notes),
  );
  if (plan.totalLength != BackupCryptoV2.maxArchiveBytes) {
    plan.dispose();
    throw StateError('The generated archive does not reach the 64 MiB limit.');
  }
  try {
    final encrypted = await BackupCryptoV2.encrypt(
      password: _password,
      archive: plan.encode(readBlob: _syntheticMedia),
      destination: _FileTransactionalSink(output),
    );
    if (encrypted.archiveBytes != BackupCryptoV2.maxArchiveBytes ||
        encrypted.envelopeBytes != BackupCryptoV2.maxEnvelopeBytes ||
        await output.length() != BackupCryptoV2.maxEnvelopeBytes) {
      throw StateError('The encrypted fixture has an unexpected size.');
    }
    // ignore: avoid_print
    print(
      'RESTORE_FIXTURE archive_bytes=${encrypted.archiveBytes} '
      'envelope_bytes=${encrypted.envelopeBytes} rows=$_recordCount '
      'media_bytes=$_mediaBytes',
    );
  } finally {
    plan.dispose();
  }
}

List<int> _exactNoteLengths() {
  const initialNoteLength = 100000;
  final initial = BackupArchiveCodec.prepareStream(
    schemaVersion: BackupArchiveCodec.latestSchemaVersion,
    stateVersion: 0,
    capturedAtUtcMillis: 0,
    rows: _fixtureRows(List<int>.filled(_customerCount, initialNoteLength)),
  );
  final additionalBytes = BackupCryptoV2.maxArchiveBytes - initial.totalLength;
  initial.dispose();
  if (additionalBytes < 0) {
    throw StateError('The initial synthetic archive is already over limit.');
  }
  final perRow = additionalBytes ~/ _customerCount;
  final remainder = additionalBytes % _customerCount;
  final noteLengths = List<int>.generate(
    _customerCount,
    (index) => initialNoteLength + perRow + (index < remainder ? 1 : 0),
    growable: false,
  );
  if (noteLengths.any((length) => length + 256 > 128 * 1024)) {
    throw StateError('The synthetic rows exceed the import row limit.');
  }
  return noteLengths;
}

List<BackupArchiveStreamRow> _fixtureRows(List<int> noteLengths) => [
  ...List<BackupArchiveStreamRow>.generate(
    noteLengths.length,
    (index) => BackupArchiveStreamRow(
      table: 'customers',
      values: {
        'id': 'restore-benchmark-${index.toString().padLeft(4, '0')}',
        'name': 'Synthetic customer $index',
        'email': '',
        'phone': '',
        'address': '',
        'notes': 'x' * noteLengths[index],
        'archived': 0,
      },
      blobs: const [],
    ),
    growable: false,
  ),
  BackupArchiveStreamRow(
    table: 'logo_assets',
    values: {
      'id': 'restore-benchmark-logo',
      'mime': 'image/png',
      'width': 1,
      'height': 1,
      'content_hash': 'synthetic-restore-benchmark',
      'bytes': null,
    },
    blobs: const [
      BackupArchiveBlobSource(
        id: _mediaId,
        column: 'bytes',
        sizeBytes: _mediaBytes,
      ),
    ],
  ),
];

Stream<Uint8List> _syntheticMedia(String id) async* {
  if (id != _mediaId) throw StateError('The fixture requested unknown media.');
  for (var offset = 0; offset < _mediaBytes; offset += 64 * 1024) {
    final bytes = Uint8List(64 * 1024);
    for (var index = 0; index < bytes.length; index++) {
      bytes[index] = ((offset + index) * 31 + 7) & 0xff;
    }
    yield bytes;
  }
}

final class _FileTransactionalSink implements BackupTransactionalByteSink {
  _FileTransactionalSink(this.file);

  final File file;
  RandomAccessFile? _handle;

  Future<RandomAccessFile> get _open async =>
      _handle ??= await file.open(mode: FileMode.write);

  @override
  Future<void> write(List<int> bytes) async {
    await (await _open).writeFrom(bytes);
  }

  @override
  Future<void> commit() async {
    final handle = await _open;
    await handle.flush();
    await handle.close();
    _handle = null;
  }

  @override
  Future<void> discard() async {
    final handle = _handle;
    _handle = null;
    if (handle != null) await handle.close();
    if (await file.exists()) await file.delete();
  }
}
