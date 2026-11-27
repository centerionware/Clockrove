import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';

class AutoBackupSettings {
  const AutoBackupSettings({
    required this.enabled,
    required this.folderSelected,
    required this.folderAvailable,
    required this.encrypt,
    required this.hasPassword,
    this.folderLabel,
    this.lastSuccessMillis,
    this.lastAttemptMillis,
    this.lastResult,
    this.lastFileName,
  });

  final bool enabled;
  final bool folderSelected;
  final bool folderAvailable;
  final bool encrypt;
  final bool hasPassword;
  final String? folderLabel;
  final int? lastSuccessMillis;
  final int? lastAttemptMillis;
  final String? lastResult;
  final String? lastFileName;

  bool get folderReady => folderSelected && folderAvailable;

  factory AutoBackupSettings.decode(Map<Object?, Object?> value) =>
      AutoBackupSettings(
        enabled: value['enabled'] as bool? ?? false,
        folderSelected: value['folderSelected'] as bool? ?? false,
        folderAvailable: value['folderAvailable'] as bool? ?? false,
        encrypt: value['encrypt'] as bool? ?? false,
        hasPassword: value['hasPassword'] as bool? ?? false,
        folderLabel: value['folderLabel'] as String?,
        lastSuccessMillis: value['lastSuccessMillis'] as int?,
        lastAttemptMillis: value['lastAttemptMillis'] as int?,
        lastResult: value['lastResult'] as String?,
        lastFileName: value['lastFileName'] as String?,
      );
}

class AutoBackupException implements Exception {
  const AutoBackupException(this.code);

  final String code;

  @override
  String toString() => 'AutoBackupException($code)';
}

abstract interface class AutoBackupSettingsApi {
  Future<AutoBackupSettings> read();
  Future<AutoBackupSettings> save({
    required bool enabled,
    required bool encrypt,
    String? password,
    bool clearPassword = false,
  });
  Future<AutoBackupSettings?> chooseFolder();
  Future<AutoBackupSettings> clearFolder();
  Future<void> runNow();
}

class MethodChannelAutoBackupSettingsApi implements AutoBackupSettingsApi {
  const MethodChannelAutoBackupSettingsApi();

  static const _channel = MethodChannel('clockrove/auto-backup-settings/v1');

  @override
  Future<AutoBackupSettings> read() => _invoke('read');

  @override
  Future<AutoBackupSettings> save({
    required bool enabled,
    required bool encrypt,
    String? password,
    bool clearPassword = false,
  }) => _invoke('save', {
    'enabled': enabled,
    'encrypt': encrypt,
    'password': password,
    'clearPassword': clearPassword,
  });

  @override
  Future<AutoBackupSettings?> chooseFolder() async {
    try {
      final value = await _channel.invokeMapMethod<Object?, Object?>(
        'chooseFolder',
      );
      return value == null ? null : AutoBackupSettings.decode(value);
    } on PlatformException catch (error) {
      throw AutoBackupException(error.code);
    } on MissingPluginException {
      throw const AutoBackupException('SETTINGS_UNAVAILABLE');
    }
  }

  @override
  Future<AutoBackupSettings> clearFolder() => _invoke('clearFolder');

  @override
  Future<void> runNow() async {
    try {
      await _channel.invokeMethod<void>('runNow');
    } on PlatformException catch (error) {
      throw AutoBackupException(error.code);
    } on MissingPluginException {
      throw const AutoBackupException('SETTINGS_UNAVAILABLE');
    }
  }

  Future<AutoBackupSettings> _invoke(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    try {
      final value = await _channel.invokeMapMethod<Object?, Object?>(
        method,
        arguments,
      );
      if (value == null)
        throw const AutoBackupException('SETTINGS_UNAVAILABLE');
      return AutoBackupSettings.decode(value);
    } on PlatformException catch (error) {
      throw AutoBackupException(error.code);
    } on MissingPluginException {
      throw const AutoBackupException('SETTINGS_UNAVAILABLE');
    }
  }
}

final autoBackupSettingsApiProvider = Provider<AutoBackupSettingsApi>(
  (ref) => const MethodChannelAutoBackupSettingsApi(),
);

class AutoBackupSettingsSection extends ConsumerStatefulWidget {
  const AutoBackupSettingsSection({super.key, this.api});

  final AutoBackupSettingsApi? api;

  @override
  ConsumerState<AutoBackupSettingsSection> createState() =>
      _AutoBackupSettingsSectionState();
}

class _AutoBackupSettingsSectionState
    extends ConsumerState<AutoBackupSettingsSection> {
  late final AutoBackupSettingsApi _api;
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  AutoBackupSettings? _settings;
  AppMessage? _loadError;
  AppMessage? _message;
  AppMessage? _passwordError;
  AppMessage? _confirmationError;
  bool _busy = false;
  bool _initialized = false;
  bool _enabled = false;
  bool _encrypt = false;

  @override
  void initState() {
    super.initState();
    _api = widget.api ?? ref.read(autoBackupSettingsApiProvider);
    _load();
  }

  @override
  void dispose() {
    _password.clear();
    _confirmation.clear();
    _password.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final settings = await _api.read();
      if (!mounted) return;
      setState(() {
        _settings = settings;
        _loadError = null;
        if (!_initialized) {
          _enabled = settings.enabled;
          _encrypt = settings.encrypt;
          _initialized = true;
        }
      });
    } on AutoBackupException catch (error) {
      if (mounted) setState(() => _loadError = _messageFor(error.code));
    } on Object {
      if (mounted) {
        setState(() => _loadError = AppMessage.autoBackupSettingsUnavailable);
      }
    }
  }

  AutoBackupSettings? get _current => _settings;

  Future<void> _chooseFolder() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
      _passwordError = null;
      _confirmationError = null;
    });
    try {
      final updated = await _api.chooseFolder();
      if (updated != null && mounted) {
        setState(() => _settings = updated);
      }
    } on AutoBackupException catch (error) {
      if (mounted) setState(() => _message = _messageFor(error.code));
    } on Object {
      if (mounted) {
        setState(() => _message = AppMessage.autoBackupSaveFailed);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool _validatePassword() {
    if (!_encrypt || _password.text.isEmpty) {
      setState(() {
        _passwordError = null;
        _confirmationError = null;
      });
      return true;
    }
    final bytes = utf8.encode(_password.text).length;
    setState(() {
      _passwordError = bytes < 12
          ? AppMessage.autoBackupPasswordTooShort
          : bytes > 1024
          ? AppMessage.autoBackupPasswordTooLong
          : null;
      _confirmationError = _confirmation.text != _password.text
          ? AppMessage.autoBackupPasswordMismatch
          : null;
    });
    return _passwordError == null && _confirmationError == null;
  }

  Future<void> _save() async {
    if (_busy) return;
    final current = _current;
    if (_enabled && !(current?.folderReady ?? false)) {
      setState(() => _message = AppMessage.autoBackupFolderRequired);
      return;
    }
    if (!_validatePassword()) return;
    final needsPassword = _encrypt && !(current?.hasPassword ?? false);
    if (needsPassword && _password.text.isEmpty) {
      setState(() => _passwordError = AppMessage.autoBackupPasswordRequired);
      return;
    }
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final saved = await _api.save(
        enabled: _enabled,
        encrypt: _encrypt,
        password: _password.text.isEmpty ? null : _password.text,
      );
      if (!mounted) return;
      _password.clear();
      _confirmation.clear();
      setState(() {
        _settings = saved;
        _enabled = saved.enabled;
        _encrypt = saved.encrypt;
        _message = AppMessage.autoBackupSaved;
      });
    } on AutoBackupException catch (error) {
      if (mounted) setState(() => _message = _messageFor(error.code));
    } on Object {
      if (mounted) setState(() => _message = AppMessage.autoBackupSaveFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _runNow() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      await _api.runNow();
      if (mounted) setState(() => _message = AppMessage.autoBackupQueued);
    } on AutoBackupException catch (error) {
      if (mounted) setState(() => _message = _messageFor(error.code));
    } on Object {
      if (mounted) {
        setState(() => _message = AppMessage.autoBackupSaveFailed);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  AppMessage _messageFor(String code) => switch (code) {
    'FOLDER_REQUIRED' => AppMessage.autoBackupFolderRequired,
    'FOLDER_UNAVAILABLE' => AppMessage.autoBackupFolderUnavailable,
    'FOLDER_PERMISSION' => AppMessage.autoBackupFolderPermission,
    'PASSWORD_REQUIRED' => AppMessage.autoBackupPasswordRequired,
    'NO_DOCUMENT_PICKER' => AppMessage.autoBackupNoPicker,
    'SETTINGS_UNAVAILABLE' => AppMessage.autoBackupSettingsUnavailable,
    _ => AppMessage.autoBackupSaveFailed,
  };

  String _date(int millis) {
    final value = DateTime.fromMillisecondsSinceEpoch(millis).toLocal();
    final localizations = MaterialLocalizations.of(context);
    return '${localizations.formatMediumDate(value)} · '
        '${localizations.formatTimeOfDay(TimeOfDay.fromDateTime(value))}';
  }

  @override
  Widget build(BuildContext context) {
    final settings = _settings;
    if (_loadError != null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            AppMessage.autoBackupTitle.of(context),
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          Text(_loadError!.of(context)),
          TextButton(
            onPressed: _busy ? null : _load,
            child: Text(AppMessage.automationTryAgain.of(context)),
          ),
        ],
      );
    }
    if (settings == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final colors = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          AppMessage.autoBackupTitle.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 8),
        Text(AppMessage.autoBackupHelp.of(context)),
        const SizedBox(height: 4),
        Text(AppMessage.autoBackupPlan.of(context)),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  AppMessage.autoBackupFolder.of(context),
                  style: Theme.of(context).textTheme.labelLarge,
                ),
                const SizedBox(height: 4),
                Text(
                  settings.folderReady
                      ? (settings.folderLabel ??
                            AppMessage.autoBackupFolder.of(context))
                      : AppMessage.autoBackupNoFolder.of(context),
                ),
                if (settings.folderSelected && !settings.folderAvailable)
                  Text(
                    AppMessage.autoBackupFolderUnavailable.of(context),
                    style: TextStyle(color: colors.error),
                  ),
                const SizedBox(height: 8),
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: OutlinedButton.icon(
                    key: const ValueKey('auto-backup-folder'),
                    onPressed: _busy ? null : _chooseFolder,
                    icon: const Icon(Icons.folder_outlined),
                    label: Text(AppMessage.autoBackupChooseFolder.of(context)),
                  ),
                ),
              ],
            ),
          ),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          key: const ValueKey('auto-backup-enabled'),
          value: _enabled,
          onChanged: _busy ? null : (value) => setState(() => _enabled = value),
          title: Text(AppMessage.autoBackupEnable.of(context)),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          key: const ValueKey('auto-backup-encrypt'),
          value: _encrypt,
          onChanged: _busy
              ? null
              : (value) => setState(() {
                  _encrypt = value;
                  _passwordError = null;
                  _confirmationError = null;
                }),
          title: Text(AppMessage.autoBackupEncrypt.of(context)),
        ),
        if (!_encrypt)
          Text(
            AppMessage.autoBackupPlainWarning.of(context),
            style: TextStyle(color: colors.error),
          ),
        if (_encrypt) ...[
          const SizedBox(height: 8),
          TextField(
            key: const ValueKey('auto-backup-password'),
            controller: _password,
            enabled: !_busy,
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
            onChanged: (_) => setState(() {
              _passwordError = null;
              _confirmationError = null;
            }),
            decoration: InputDecoration(
              labelText: AppMessage.autoBackupPassword.of(context),
              helperText: settings.hasPassword && _password.text.isEmpty
                  ? AppMessage.autoBackupPasswordSaved.of(context)
                  : AppMessage.autoBackupPasswordHelp.of(context),
              errorText: _passwordError?.of(context),
              helperMaxLines: 3,
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('auto-backup-password-confirmation'),
            controller: _confirmation,
            enabled: !_busy,
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
            onChanged: (_) => setState(() {
              _passwordError = null;
              _confirmationError = null;
            }),
            decoration: InputDecoration(
              labelText: AppMessage.autoBackupConfirmPassword.of(context),
              errorText: _confirmationError?.of(context),
            ),
          ),
        ],
        const SizedBox(height: 12),
        Text(
          AppMessage.autoBackupPrivacy.of(context),
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 12),
        if (_message != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              _message!.of(context),
              key: const ValueKey('auto-backup-message'),
              style: TextStyle(
                color:
                    _message == AppMessage.autoBackupSaved ||
                        _message == AppMessage.autoBackupQueued
                    ? colors.primary
                    : colors.error,
              ),
            ),
          ),
        FilledButton(
          key: const ValueKey('auto-backup-save'),
          onPressed: _busy ? null : _save,
          child: Text(AppMessage.autoBackupSave.of(context)),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: const ValueKey('auto-backup-run-now'),
          onPressed: _busy || !settings.enabled ? null : _runNow,
          icon: const Icon(Icons.backup_outlined),
          label: Text(AppMessage.autoBackupRunNow.of(context)),
        ),
        const SizedBox(height: 12),
        Text(
          settings.lastSuccessMillis == null
              ? AppMessage.autoBackupNever.of(context)
              : AppMessage.autoBackupLastSuccess.format(context, {
                  'time': _date(settings.lastSuccessMillis!),
                }),
        ),
        if (settings.lastResult != null && settings.lastResult != 'SUCCESS')
          Text(
            AppMessage.autoBackupLastFailed.format(context, {
              'code': settings.lastResult!,
            }),
            style: TextStyle(color: colors.error),
          ),
        if (settings.lastFileName != null)
          Text(
            AppMessage.autoBackupLastFile.format(context, {
              'name': settings.lastFileName!,
            }),
          ),
      ],
    );
  }
}
