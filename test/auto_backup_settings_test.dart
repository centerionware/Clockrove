import 'package:clockrove/backup/auto_backup_settings.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeAutoBackupApi implements AutoBackupSettingsApi {
  AutoBackupSettings current = const AutoBackupSettings(
    enabled: false,
    folderSelected: false,
    folderAvailable: false,
    encrypt: false,
    hasPassword: false,
  );

  final saves = <({bool enabled, bool encrypt, String? password})>[];
  int runNowCalls = 0;

  @override
  Future<AutoBackupSettings> read() async => current;

  @override
  Future<AutoBackupSettings> save({
    required bool enabled,
    required bool encrypt,
    String? password,
    bool clearPassword = false,
  }) async {
    saves.add((enabled: enabled, encrypt: encrypt, password: password));
    current = AutoBackupSettings(
      enabled: enabled,
      folderSelected: current.folderSelected,
      folderAvailable: current.folderAvailable,
      encrypt: encrypt,
      hasPassword: encrypt && (password != null || current.hasPassword),
      folderLabel: current.folderLabel,
    );
    return current;
  }

  @override
  Future<AutoBackupSettings?> chooseFolder() async {
    current = AutoBackupSettings(
      enabled: current.enabled,
      folderSelected: true,
      folderAvailable: true,
      encrypt: current.encrypt,
      hasPassword: current.hasPassword,
      folderLabel: 'Drive backups',
    );
    return current;
  }

  @override
  Future<AutoBackupSettings> clearFolder() async => current;

  @override
  Future<void> runNow() async {
    runNowCalls++;
  }
}

Future<void> _pump(WidgetTester tester, _FakeAutoBackupApi api) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: AutoBackupSettingsSection(api: api),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _tap(WidgetTester tester, Key key) async {
  final finder = find.byKey(key);
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('requires a folder before enabling automatic backups', (
    tester,
  ) async {
    final api = _FakeAutoBackupApi();
    await _pump(tester, api);

    expect(find.text('Automatic backups'), findsOneWidget);
    expect(find.text('No folder selected'), findsOneWidget);
    await _tap(tester, const ValueKey('auto-backup-enabled'));
    await _tap(tester, const ValueKey('auto-backup-save'));

    expect(find.text('Choose a backup folder first.'), findsOneWidget);
    expect(api.saves, isEmpty);
  });

  testWidgets('chooses a folder and saves an enabled plaintext plan', (
    tester,
  ) async {
    final api = _FakeAutoBackupApi();
    await _pump(tester, api);

    await _tap(tester, const ValueKey('auto-backup-folder'));
    expect(find.text('Drive backups'), findsOneWidget);
    await _tap(tester, const ValueKey('auto-backup-enabled'));
    await _tap(tester, const ValueKey('auto-backup-save'));

    expect(api.saves, hasLength(1));
    expect(api.saves.single.enabled, isTrue);
    expect(api.saves.single.encrypt, isFalse);
    expect(api.saves.single.password, isNull);
    expect(find.text('Automatic backup settings saved.'), findsOneWidget);
    expect(find.textContaining('not encrypted'), findsOneWidget);
  });

  testWidgets('validates a new encryption password before saving', (
    tester,
  ) async {
    final api = _FakeAutoBackupApi();
    await _pump(tester, api);

    await _tap(tester, const ValueKey('auto-backup-folder'));
    await _tap(tester, const ValueKey('auto-backup-encrypt'));
    await tester.enterText(
      find.byKey(const ValueKey('auto-backup-password')),
      'short',
    );
    await _tap(tester, const ValueKey('auto-backup-save'));
    expect(find.text('Use at least 12 UTF-8 bytes.'), findsOneWidget);
    expect(api.saves, isEmpty);

    await tester.enterText(
      find.byKey(const ValueKey('auto-backup-password')),
      'long-enough-password',
    );
    await tester.enterText(
      find.byKey(const ValueKey('auto-backup-password-confirmation')),
      'long-enough-password',
    );
    await _tap(tester, const ValueKey('auto-backup-save'));

    expect(api.saves, hasLength(1));
    expect(api.saves.single.encrypt, isTrue);
    expect(api.saves.single.password, 'long-enough-password');
  });

  testWidgets('runs now only after automatic backups are enabled', (
    tester,
  ) async {
    final api = _FakeAutoBackupApi();
    await _pump(tester, api);

    await _tap(tester, const ValueKey('auto-backup-folder'));
    await _tap(tester, const ValueKey('auto-backup-enabled'));
    await _tap(tester, const ValueKey('auto-backup-save'));
    await _tap(tester, const ValueKey('auto-backup-run-now'));

    expect(api.runNowCalls, 1);
    expect(find.textContaining('Backup queued'), findsOneWidget);
  });
}
