import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'map_provider_settings.dart';

class MapProviderSettingsScreen extends ConsumerWidget {
  const MapProviderSettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: Text(AppMessage.mapProvider.of(context))),
    body: ref
        .watch(mapProviderSettingsProvider)
        .when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: FilledButton.tonal(
              onPressed: () => ref.invalidate(mapProviderSettingsProvider),
              child: Text(AppMessage.retryMapSettings.of(context)),
            ),
          ),
          data: (settings) => _MapProviderForm(initial: settings),
        ),
  );
}

class _MapProviderForm extends ConsumerStatefulWidget {
  const _MapProviderForm({required this.initial});
  final MapProviderSettings initial;

  @override
  ConsumerState<_MapProviderForm> createState() => _MapProviderFormState();
}

class _MapProviderFormState extends ConsumerState<_MapProviderForm> {
  late String _provider = widget.initial.provider;
  late final _url = TextEditingController(text: widget.initial.urlTemplate);
  late final _attribution = TextEditingController(
    text: widget.initial.attribution,
  );
  late final _attributionUrl = TextEditingController(
    text: widget.initial.attributionUrl,
  );
  late final _header = TextEditingController(text: widget.initial.authHeader);
  final _secret = TextEditingController();
  bool _forgetCredential = false;
  bool _busy = false;
  AppMessage? _message;
  AppMessage? _validationError;

  @override
  void dispose() {
    _url.dispose();
    _attribution.dispose();
    _attributionUrl.dispose();
    _header.dispose();
    _secret.clear();
    _secret.dispose();
    super.dispose();
  }

  MapProviderSettings _draft() => _provider == 'OSM'
      ? const MapProviderSettings()
      : MapProviderSettings(
          provider: _provider,
          urlTemplate: _url.text.trim(),
          attribution: _attribution.text.trim(),
          attributionUrl: _attributionUrl.text.trim(),
          authHeader: _header.text.trim(),
          hasCredential:
              _secret.text.isNotEmpty ||
              (widget.initial.hasCredential && !_forgetCredential),
        );

  Future<void> _save() async {
    final draft = _draft();
    final credential = _provider == 'CUSTOM' && _secret.text.isNotEmpty
        ? _secret.text
        : null;
    final error = mapProviderSettingsError(draft, authSecret: credential ?? '');
    setState(() {
      _validationError = error;
      _message = null;
    });
    if (error != null) return;
    setState(() => _busy = true);
    try {
      await ref
          .read(mapProviderSettingsApiProvider)
          .save(
            draft,
            authSecret: credential,
            removeCredential: _provider == 'OSM' || _forgetCredential,
          );
      ref.invalidate(mapProviderSettingsProvider);
      if (mounted) Navigator.pop(context);
    } on MapProviderInputException catch (error) {
      if (mounted) setState(() => _validationError = error.key);
    } catch (_) {
      if (mounted) {
        setState(
          () => _message = AppMessage.mapSaveFailed,
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _reset() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: Text(AppMessage.useOpenStreetMap.of(dialog)),
        content: Text(AppMessage.mapResetWarning.of(dialog)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialog, false),
            child: Text(AppMessage.keepSettings.of(dialog)),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialog, true),
            child: Text(AppMessage.resetMapProvider.of(dialog)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      await ref.read(mapProviderSettingsApiProvider).reset();
      ref.invalidate(mapProviderSettingsProvider);
      if (mounted) Navigator.pop(context);
    } catch (_) {
      if (mounted) {
        setState(
          () => _message = AppMessage.mapResetFailed,
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final android = Theme.of(context).platform == TargetPlatform.android;
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text(
          AppMessage.mapTiles.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 8),
        Text(AppMessage.mapProviderPrivacy.of(context)),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          key: const ValueKey('map-provider-choice'),
          initialValue: _provider,
          decoration: InputDecoration(
            labelText: AppMessage.tileProvider.of(context),
          ),
          items: [
            DropdownMenuItem(
              value: 'OSM',
              child: Text(AppMessage.openStreetMapDefault.of(context)),
            ),
            if (android)
              DropdownMenuItem(
                value: 'CUSTOM',
                child: Text(AppMessage.customHttpsTiles.of(context)),
              ),
          ],
          onChanged: _busy
              ? null
              : (value) {
                  if (value != null) {
                    setState(() {
                      _provider = value;
                      _validationError = null;
                      _message = null;
                    });
                  }
                },
        ),
        if (_provider == 'CUSTOM') ...[
          const SizedBox(height: 20),
          TextField(
            key: const ValueKey('map-tile-url'),
            controller: _url,
            keyboardType: TextInputType.url,
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: AppMessage.tileUrlTemplate.of(context),
              helperText: AppMessage.mapTileTokens.of(context),
              helperMaxLines: 2,
            ),
            maxLength: 2048,
          ),
          TextField(
            key: const ValueKey('map-attribution'),
            controller: _attribution,
            decoration: InputDecoration(
              labelText: AppMessage.requiredMapAttribution.of(context),
            ),
            maxLength: 300,
          ),
          TextField(
            key: const ValueKey('map-attribution-url'),
            controller: _attributionUrl,
            keyboardType: TextInputType.url,
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: AppMessage.optionalAttributionLink.of(context),
            ),
            maxLength: 2048,
          ),
          TextField(
            key: const ValueKey('map-auth-header'),
            controller: _header,
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: AppMessage.authHeaderName.of(context),
              helperText: AppMessage.mapAuthHeaderExamples.of(context),
            ),
            maxLength: 64,
          ),
          TextField(
            key: const ValueKey('map-auth-secret'),
            controller: _secret,
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
            onChanged: (value) {
              if (value.isEmpty && _forgetCredential) return;
              setState(() {
                _forgetCredential = false;
                _validationError = null;
              });
            },
            decoration: InputDecoration(
              labelText: AppMessage.optionalAuthValue.of(context),
              helperText: widget.initial.hasCredential && !_forgetCredential
                  ? AppMessage.mapKeepCredential.of(context)
                  : AppMessage.mapBearerHelp.of(context),
              helperMaxLines: 2,
            ),
            maxLength: 4096,
          ),
          if (widget.initial.hasCredential)
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              value: _forgetCredential,
              onChanged: _busy
                  ? null
                  : (value) {
                      setState(() => _forgetCredential = value ?? false);
                      if (_forgetCredential) _secret.clear();
                    },
              title: Text(AppMessage.removeSavedAuth.of(context)),
            ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                AppMessage.mapCredentialPrivacy.of(context),
              ),
            ),
          ),
        ],
        if (!android)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              AppMessage.mapCredentialsAndroidOnly.of(context),
            ),
          ),
        const SizedBox(height: 16),
        if (_validationError != null)
          Text(
            _validationError!.of(context),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_message != null)
          Text(
            _message!.of(context),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: _busy ? null : _save,
          icon: _busy
              ? const SizedBox.square(
                  dimension: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.save_outlined),
          label: Text(AppMessage.saveMapSettings.of(context)),
        ),
        const SizedBox(height: 8),
        TextButton.icon(
          onPressed: _busy ? null : _reset,
          icon: const Icon(Icons.restart_alt),
          label: Text(AppMessage.resetToOpenStreetMap.of(context)),
        ),
        const SizedBox(height: 24),
        Text(AppMessage.mapGoogleSdkNote.of(context)),
      ],
    );
  }
}
