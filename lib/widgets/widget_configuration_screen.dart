import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../business/business_api.dart';
import '../business/business_command.dart';
import '../l10n/app_localizations.dart';
import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

const widgetChannel = MethodChannel('clockrove/widget/v1');
final widgetConfigurationProvider = FutureProvider<Map<String, Object?>>((
  ref,
) async {
  final value = await widgetChannel.invokeMapMethod<String, Object?>(
    'configuration',
  );
  if (value == null) throw const FormatException('Missing widget settings');
  return value;
});

class WidgetConfigurationScreen extends ConsumerStatefulWidget {
  const WidgetConfigurationScreen({super.key});
  @override
  ConsumerState<WidgetConfigurationScreen> createState() =>
      _WidgetConfigurationScreenState();
}

class _WidgetConfigurationScreenState
    extends ConsumerState<WidgetConfigurationScreen> {
  String? _job;
  bool _initialized = false, _pending = false, _saved = false;
  AppMessage? _finishError;
  Future<void> _finish() async {
    try {
      await widgetChannel.invokeMethod<void>('finish');
    } catch (_) {
      if (mounted) {
        setState(() => _finishError = AppMessage.widgetFinishRetry);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final configuration = ref.watch(widgetConfigurationProvider);
    final state = ref.watch(timerSnapshotProvider);
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(title: Text(AppMessage.widgetSettings.of(context))),
        body: configuration.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: FilledButton(
              onPressed: () => ref.invalidate(widgetConfigurationProvider),
              child: Text(AppMessage.widgetRetrySettings.of(context)),
            ),
          ),
          data: (config) => state.when(
            skipLoadingOnReload: true,
            skipError: true,
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => Center(
              child: FilledButton(
                onPressed: () => ref.invalidate(timerSnapshotProvider),
                child: Text(AppMessage.retryJobs.of(context)),
              ),
            ),
            data: (snapshot) {
              if (!_initialized) {
                _job = config['jobId'] as String?;
                _initialized = true;
              }
              final tasksOnly = config['taskOnly'] == true;
              final missing =
                  _job != null && !snapshot.jobs.any((job) => job.id == _job);
              return ListView(
                padding: const EdgeInsets.all(24),
                children: [
                  if (state.hasError) ...[
                    Text(AppMessage.widgetRefreshJobsFailed.of(context)),
                    TextButton(
                      onPressed: () => ref.invalidate(timerSnapshotProvider),
                      child: Text(AppMessage.retryJobs.of(context)),
                    ),
                  ],
                  Text(
                    (tasksOnly
                            ? AppMessage.widgetChooseTaskJobs
                            : AppMessage.widgetChooseDefaultJob)
                        .of(context),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    (tasksOnly
                            ? AppMessage.widgetTaskWindow
                            : AppMessage.widgetTimerBehavior)
                        .of(context),
                  ),
                  const SizedBox(height: 20),
                  if (missing)
                    Text(AppMessage.widgetSelectedJobUnavailable.of(context)),
                  RadioGroup<String>(
                    groupValue: _job ?? '',
                    onChanged: (value) {
                      if (!_pending && !_saved) {
                        setState(() => _job = value == '' ? null : value);
                      }
                    },
                    child: Column(
                      children: [
                        RadioListTile<String>(
                          enabled: !_pending && !_saved,
                          value: '',
                          title: Text(
                            (tasksOnly
                                    ? AppMessage.widgetAllJobs
                                    : AppMessage.widgetChooseEachTime)
                                .of(context),
                          ),
                        ),
                        for (final job in snapshot.jobs)
                          RadioListTile<String>(
                            enabled: !_pending && !_saved,
                            value: job.id,
                            title: Text(job.name),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  if (_saved)
                    FilledButton(
                      onPressed: _finish,
                      child: Text(AppMessage.done.of(context)),
                    )
                  else
                    BusinessCommand(
                      key: const ValueKey('widget-save'),
                      label: AppMessage.widgetSave.of(context),
                      enabled: !missing,
                      onPendingChanged: (pending) =>
                          setState(() => _pending = pending),
                      create: () {
                        final id = newBusinessId(), job = _job;
                        final version = snapshot.version;
                        return () async {
                          final result = await widgetChannel
                              .invokeMapMethod<String, Object?>('save', {
                                'id': id,
                                'version': version,
                                'jobId': job,
                              });
                          if (result == null) {
                            throw const FormatException(
                              'Missing widget save result',
                            );
                          }
                          return TimerOutcome(
                            result['version'] as int,
                            result['conflict'] as String?,
                          );
                        };
                      },
                      onCommitted: () {
                        setState(() => _saved = true);
                        _finish();
                      },
                    ),
                  if (_finishError != null) Text(_finishError!.of(context)),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
