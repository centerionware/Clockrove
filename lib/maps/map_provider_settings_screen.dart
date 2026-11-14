import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'map_provider_settings.dart';

class MapProviderSettingsScreen extends ConsumerWidget {
  const MapProviderSettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: const Text('Map provider')),
    body: ref
        .watch(mapProviderSettingsProvider)
        .when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: FilledButton.tonal(
              onPressed: () => ref.invalidate(mapProviderSettingsProvider),
              child: const Text('Retry map settings'),
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
  String? _message;
  String? _validationError;

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
    } on FormatException catch (e) {
      if (mounted) setState(() => _message = e.message);
    } catch (_) {
      if (mounted) {
        setState(
          () => _message = 'Map settings could not be saved. Check the values and try again.',
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
        title: const Text('Use OpenStreetMap?'),
        content: const Text(
          'This removes the custom tile settings and saved authentication value from this device.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialog, false),
            child: const Text('Keep settings'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialog, true),
            child: const Text('Reset map provider'),
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
          () => _message =
              'Map settings could not be reset. Try again or reopen Settings.',
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
        Text('Map tiles', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        const Text(
          'OpenStreetMap works without an account or key. Tile providers receive the areas you view. Use a provider that allows your usage and keep its required attribution visible.',
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          key: const ValueKey('map-provider-choice'),
          initialValue: _provider,
          decoration: const InputDecoration(labelText: 'Tile provider'),
          items: [
            const DropdownMenuItem(
              value: 'OSM',
              child: Text('OpenStreetMap (default)'),
            ),
            if (android)
              const DropdownMenuItem(
                value: 'CUSTOM',
                child: Text('Custom HTTPS tile server'),
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
            decoration: const InputDecoration(
              labelText: 'HTTPS tile URL template',
              helperText: 'Use {z}, {x}, and {y}. Do not put keys in the URL.',
              helperMaxLines: 2,
            ),
            maxLength: 2048,
          ),
          TextField(
            key: const ValueKey('map-attribution'),
            controller: _attribution,
            decoration: const InputDecoration(
              labelText: 'Required map attribution',
            ),
            maxLength: 300,
          ),
          TextField(
            key: const ValueKey('map-attribution-url'),
            controller: _attributionUrl,
            keyboardType: TextInputType.url,
            autocorrect: false,
            enableSuggestions: false,
            decoration: const InputDecoration(
              labelText: 'Attribution link (optional)',
            ),
            maxLength: 2048,
          ),
          TextField(
            key: const ValueKey('map-auth-header'),
            controller: _header,
            autocorrect: false,
            enableSuggestions: false,
            decoration: const InputDecoration(
              labelText: 'Authentication header name',
              helperText: 'For example Authorization or X-API-Key.',
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
              labelText: 'Authentication value (optional)',
              helperText: widget.initial.hasCredential && !_forgetCredential
                  ? 'A credential is saved. Leave blank to keep it, or enter a replacement.'
                  : 'For bearer authentication, enter Bearer followed by the token.',
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
              title: const Text('Remove saved authentication value'),
            ),
          const Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                'The authentication value is encrypted with Android Keystore and is not included in backups. Use a header-based credential; keys in tile URLs can leak through caches and logs.',
              ),
            ),
          ),
        ],
        if (!android)
          const Padding(
            padding: EdgeInsets.only(top: 12),
            child: Text(
              'Custom provider credentials are currently supported on Android only.',
            ),
          ),
        const SizedBox(height: 16),
        if (_validationError != null)
          Text(
            _validationError!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_message != null)
          Text(
            _message!,
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
          label: const Text('Save map settings'),
        ),
        const SizedBox(height: 8),
        TextButton.icon(
          onPressed: _busy ? null : _reset,
          icon: const Icon(Icons.restart_alt),
          label: const Text('Reset to OpenStreetMap'),
        ),
        const SizedBox(height: 24),
        const Text(
          'Google Maps SDK uses an app-configured Android key. This screen accepts runtime credentials for compatible HTTPS raster providers, not Google SDK keys.',
        ),
      ],
    );
  }
}
