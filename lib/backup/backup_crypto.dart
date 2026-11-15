import 'dart:convert';
import 'dart:isolate';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// Password-encryption envelope for future portable Clockrove archives.
///
/// The plaintext must be validated and serialized by the backup repository.
/// This class authenticates the fixed-size envelope header and never parses
/// decrypted plaintext.
abstract final class BackupCrypto {
  static const int maxArchiveBytes = 64 * 1024 * 1024;
  static const int _headerLength = 44;
  static const int _saltLength = 16;
  static const int _formatVersion = 1;
  static const int _argon2idId = 1;
  static const int _aes256GcmId = 1;
  // Do not change the version-1 KDF profile after releasing backups. New
  // profiles need a new format version while readers retain this one.
  static const int _memoryKiB = 19 * 1024;
  static const int _iterations = 2;
  static const int _parallelism = 1;
  static const int _nonceLength = 12;
  static const int _macLength = 16;
  static const List<int> _magic = [
    0x43,
    0x4c,
    0x4b,
    0x52,
    0x4f,
    0x56,
    0x45,
    0x31,
  ]; // CLKROVE1

  /// Encrypts serialized archive bytes. Keep [password] in memory only.
  static Future<Uint8List> encrypt({
    required String password,
    required Uint8List archive,
  }) async {
    _validatePassword(password);
    if (archive.isEmpty || archive.length > maxArchiveBytes) {
      throw const FormatException('The backup is outside the supported size.');
    }

    final salt = _secureBytes(_saltLength);
    final header = _header(archive.length, salt);
    final transferred = TransferableTypedData.fromList([header, archive]);
    final output = await Isolate.run(
      () => _encryptWorker(transferred, password),
    );
    return output.materialize().asUint8List();
  }

  /// Authenticates the envelope before returning plaintext bytes to a caller.
  /// Callers must not parse any archive payload before this future succeeds.
  static Future<Uint8List> decrypt({
    required String password,
    required Uint8List envelope,
  }) async {
    _validatePassword(password);
    _validateHeader(envelope);
    final transferred = TransferableTypedData.fromList([envelope]);
    try {
      final output = await Isolate.run(
        () => _decryptWorker(transferred, password),
      );
      return output.materialize().asUint8List();
    } on Object {
      // Keep wrong-password and authenticated-payload failures indistinguishable.
      throw const FormatException(
        'The backup could not be unlocked. Check the password and file.',
      );
    }
  }

  static Uint8List _secureBytes(int length) {
    final random = Random.secure();
    return Uint8List.fromList(
      List<int>.generate(length, (_) => random.nextInt(256), growable: false),
    );
  }

  static void _validatePassword(String password) {
    final byteLength = utf8.encode(password).length;
    if (byteLength < 12 || byteLength > 1024) {
      throw const FormatException(
        'Use a backup password between 12 and 1024 UTF-8 bytes.',
      );
    }
  }

  static Uint8List _header(int payloadLength, Uint8List salt) {
    final result = Uint8List(_headerLength);
    result.setRange(0, _magic.length, _magic);
    final fields = ByteData.sublistView(result);
    fields.setUint16(8, _formatVersion, Endian.big);
    fields.setUint16(10, _headerLength, Endian.big);
    fields.setUint32(12, _memoryKiB, Endian.big);
    fields.setUint32(16, _iterations, Endian.big);
    result[20] = _parallelism;
    result[21] = _saltLength;
    result[22] = _argon2idId;
    result[23] = _aes256GcmId;
    fields.setUint32(24, payloadLength, Endian.big);
    result.setRange(28, _headerLength, salt);
    return result;
  }

  static void _validateHeader(Uint8List envelope) {
    if (envelope.length < _headerLength + _nonceLength + _macLength ||
        envelope.length >
            _headerLength + maxArchiveBytes + _nonceLength + _macLength) {
      throw const FormatException('This backup file has an invalid size.');
    }
    for (var i = 0; i < _magic.length; i++) {
      if (envelope[i] != _magic[i]) {
        throw const FormatException('This is not a Clockrove backup.');
      }
    }
    final fields = ByteData.sublistView(envelope, 0, _headerLength);
    if (fields.getUint16(8, Endian.big) != _formatVersion ||
        fields.getUint16(10, Endian.big) != _headerLength ||
        fields.getUint32(12, Endian.big) != _memoryKiB ||
        fields.getUint32(16, Endian.big) != _iterations ||
        envelope[20] != _parallelism ||
        envelope[21] != _saltLength ||
        envelope[22] != _argon2idId ||
        envelope[23] != _aes256GcmId) {
      throw const FormatException(
        'This backup uses unsupported or unsafe encryption settings.',
      );
    }
    final payloadLength = fields.getUint32(24, Endian.big);
    if (payloadLength == 0 ||
        payloadLength > maxArchiveBytes ||
        envelope.length !=
            _headerLength + payloadLength + _nonceLength + _macLength) {
      throw const FormatException('This backup file has an invalid size.');
    }
  }

  static Future<TransferableTypedData> _encryptWorker(
    TransferableTypedData transferred,
    String password,
  ) async {
    final request = transferred.materialize().asUint8List();
    final header = Uint8List.sublistView(request, 0, _headerLength);
    final payload = Uint8List.sublistView(request, _headerLength);
    final salt = Uint8List.sublistView(request, 28, _headerLength);
    final key = await Argon2id(
      memory: _memoryKiB,
      parallelism: _parallelism,
      iterations: _iterations,
      hashLength: 32,
    ).deriveKeyFromPassword(password: password, nonce: salt);
    final cipher = AesGcm.with256bits();
    final box = await cipher.encrypt(
      payload,
      secretKey: key,
      nonce: cipher.newNonce(),
      aad: header,
    );
    return TransferableTypedData.fromList([header, box.concatenation()]);
  }

  static Future<TransferableTypedData> _decryptWorker(
    TransferableTypedData transferred,
    String password,
  ) async {
    final envelope = transferred.materialize().asUint8List();
    final header = Uint8List.sublistView(envelope, 0, _headerLength);
    final fields = ByteData.sublistView(envelope, 0, _headerLength);
    final payloadLength = fields.getUint32(24, Endian.big);
    final salt = Uint8List.sublistView(envelope, 28, _headerLength);
    final key = await Argon2id(
      memory: _memoryKiB,
      parallelism: _parallelism,
      iterations: _iterations,
      hashLength: 32,
    ).deriveKeyFromPassword(password: password, nonce: salt);
    final encrypted = Uint8List.sublistView(envelope, _headerLength);
    final box = SecretBox.fromConcatenation(
      encrypted,
      nonceLength: _nonceLength,
      macLength: _macLength,
      copy: false,
    );
    final plaintext = await AesGcm.with256bits().decrypt(
      box,
      secretKey: key,
      aad: header,
    );
    if (plaintext.length != payloadLength) {
      throw const FormatException(
        'The authenticated backup length is invalid.',
      );
    }
    return TransferableTypedData.fromList([Uint8List.fromList(plaintext)]);
  }
}
