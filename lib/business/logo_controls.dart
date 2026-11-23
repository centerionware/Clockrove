import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';
import 'business_api.dart';
import 'business_command.dart';

class CompanyLogo {
  const CompanyLogo(this.version, this.id, this.png);
  final int version;
  final String? id;
  final Uint8List? png;
}

class LogoDraft {
  const LogoDraft(this.token, this.png);
  final String token;
  final Uint8List png;
}

class LogoApi {
  const LogoApi();
  static const _business = MethodChannel('clockrove/business/v1');
  static const _documents = MethodChannel('clockrove/documents/v1');
  Future<CompanyLogo> read() async {
    final v = (await _business.invokeMapMethod<Object?, Object?>(
      'companyLogo',
    ))!;
    return CompanyLogo(
      v['version'] as int,
      v['id'] as String?,
      v['png'] as Uint8List?,
    );
  }

  Future<LogoDraft?> pick() async {
    final v = await _documents.invokeMapMethod<Object?, Object?>('pickLogo');
    return v == null
        ? null
        : LogoDraft(v['token'] as String, v['png'] as Uint8List);
  }

  Future<TimerOutcome> _change(
    String method,
    String id,
    int version,
    Map<String, Object?> value,
  ) async {
    final v = (await _business.invokeMapMethod<String, Object?>(method, {
      'id': id,
      'version': version,
      'value': value,
    }))!;
    return TimerOutcome(v['version'] as int, v['conflict'] as String?);
  }

  Future<TimerOutcome> apply(String id, int version, String token) =>
      _change('applyLogo', id, version, {'token': token});
  Future<TimerOutcome> remove(String id, int version) =>
      _change('removeLogo', id, version, {});
}

final logoApiProvider = Provider<LogoApi>((ref) => const LogoApi());
final logoProvider = FutureProvider<CompanyLogo>((ref) {
  ref.watch(timerVersionsProvider);
  return ref.watch(logoApiProvider).read();
});

class LogoControls extends ConsumerStatefulWidget {
  const LogoControls({super.key, this.onBusyChanged});
  final ValueChanged<bool>? onBusyChanged;
  @override
  ConsumerState<LogoControls> createState() => _LogoControlsState();
}

class _LogoControlsState extends ConsumerState<LogoControls> {
  LogoDraft? _draft;
  bool _picking = false, _pending = false;
  String? _error;
  void _pendingChanged(bool value) {
    if (mounted) {
      setState(() => _pending = value);
      widget.onBusyChanged?.call(value || _picking);
    }
  }

  Future<void> _pick() async {
    if (_picking || _pending) return;
    setState(() {
      _picking = true;
      _error = null;
    });
    widget.onBusyChanged?.call(true);
    try {
      final draft = await ref.read(logoApiProvider).pick();
      if (mounted && draft != null) {
        setState(() => _draft = draft);
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = AppMessage.businessLogoChooseError.of(context),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _picking = false);
        widget.onBusyChanged?.call(_pending);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(logoProvider);
    final current = state.value;
    final png = _draft?.png ?? current?.png;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              AppMessage.businessLogoTitle.of(context),
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            if (png != null)
              SizedBox(
                height: 120,
                child: Image.memory(
                  png,
                  fit: BoxFit.contain,
                  semanticLabel: _draft == null
                      ? AppMessage.businessLogoSavedSemantic.of(context)
                      : AppMessage.businessLogoSelectedSemantic.of(context),
                ),
              ),
            if (png == null)
              Text(AppMessage.businessLogoHelp.of(context)),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            if (state.hasError && current == null)
              TextButton(
                onPressed: () => ref.invalidate(logoProvider),
                child: Text(AppMessage.businessLogoRetry.of(context)),
              ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _picking || _pending || current == null ? null : _pick,
              icon: const Icon(Icons.image_outlined),
              label: Text(
                _picking
                    ? AppMessage.businessLogoCopying.of(context)
                    : png == null
                    ? AppMessage.businessLogoChoose.of(context)
                    : AppMessage.businessLogoChange.of(context),
              ),
            ),
            if (_draft != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: BusinessCommand(
                  label: AppMessage.businessLogoUse.of(context),
                  enabled: current != null && !_picking,
                  onPendingChanged: _pendingChanged,
                  create: () {
                    final id = newBusinessId();
                    final version = current!.version;
                    final token = _draft!.token;
                    final api = ref.read(logoApiProvider);
                    return () => api.apply(id, version, token);
                  },
                  onCommitted: () {
                    setState(() => _draft = null);
                    ref.invalidate(logoProvider);
                  },
                ),
              ),
            const SizedBox(height: 12),
            BusinessCommand(
              label: AppMessage.businessLogoRemove.of(context),
              enabled: current?.id != null && _draft == null && !_picking,
              onPendingChanged: _pendingChanged,
              create: () {
                final id = newBusinessId();
                final version = current!.version;
                final api = ref.read(logoApiProvider);
                return () => api.remove(id, version);
              },
              onCommitted: () => ref.invalidate(logoProvider),
            ),
            const SizedBox(height: 8),
            Text(AppMessage.businessLogoImpactHelp.of(context)),
          ],
        ),
      ),
    );
  }
}
