import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../business/business_api.dart';
import '../business/business_command.dart';
import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

const widgetChannel = MethodChannel('clockrove/widget/v1');
final widgetConfigurationProvider = FutureProvider<Map<String, Object?>>((ref) async {
  final value = await widgetChannel.invokeMapMethod<String, Object?>('configuration');
  if (value == null) throw const FormatException('Missing widget settings');
  return value;
});

class WidgetConfigurationScreen extends ConsumerStatefulWidget {
  const WidgetConfigurationScreen({super.key});
  @override
  ConsumerState<WidgetConfigurationScreen> createState() => _WidgetConfigurationScreenState();
}

class _WidgetConfigurationScreenState extends ConsumerState<WidgetConfigurationScreen> {
  String? _job;
  bool _initialized = false, _pending = false, _saved = false;
  String? _finishError;
  Future<void> _finish() async {
    try { await widgetChannel.invokeMethod<void>('finish'); }
    catch (_) { if (mounted) setState(() => _finishError = 'Settings saved. Tap Done to close widget setup.'); }
  }
  @override
  Widget build(BuildContext context) {
    final configuration = ref.watch(widgetConfigurationProvider);
    final state = ref.watch(timerSnapshotProvider);
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(title: const Text('Widget settings')),
        body: configuration.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(child: FilledButton(onPressed: () => ref.invalidate(widgetConfigurationProvider), child: const Text('Retry widget settings'))),
          data: (config) => state.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => Center(child: FilledButton(onPressed: () => ref.invalidate(timerSnapshotProvider), child: const Text('Retry jobs'))),
            data: (snapshot) {
              if (!_initialized) { _job = config['jobId'] as String?; _initialized = true; }
              final tasksOnly = config['taskOnly'] == true;
              final missing = _job != null && !snapshot.jobs.any((job) => job.id == _job);
              return ListView(padding: const EdgeInsets.all(24), children: [
                Text(tasksOnly ? 'Choose which jobs appear in your task list.' : 'Choose a default job for one-tap clock-in, or choose a job each time.', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 12),
                Text(tasksOnly ? 'Overdue tasks and the next 7 calendar days. Completed tasks and archived jobs are hidden.' : 'The timer always shows the running job. Clock out before starting another job. Tap the job title to change these settings.'),
                const SizedBox(height: 20),
                if (missing) const Text('The selected job is unavailable. Choose an active job or picker mode.'),
                RadioGroup<String>(
                  groupValue: _job ?? '',
                  onChanged: (value) { if (!_pending && !_saved) setState(() => _job = value == '' ? null : value); },
                  child: Column(children: [
                    RadioListTile<String>(enabled: !_pending && !_saved, value: '', title: Text(tasksOnly ? 'All active jobs' : 'Choose each time')),
                    for (final job in snapshot.jobs) RadioListTile<String>(enabled: !_pending && !_saved, value: job.id, title: Text(job.name)),
                  ]),
                ),
                const SizedBox(height: 20),
                if (_saved) FilledButton(onPressed: _finish, child: const Text('Done')) else BusinessCommand(
                  label: 'Save widget', enabled: !missing,
                  onPendingChanged: (pending) => setState(() => _pending = pending),
                  create: () {
                    final id = newBusinessId(), job = _job;
                    final version = snapshot.version;
                    return () async {
                      final result = await widgetChannel.invokeMapMethod<String, Object?>('save', {'id': id, 'version': version, 'jobId': job});
                      if (result == null) throw const FormatException('Missing widget save result');
                      return TimerOutcome(result['version'] as int, result['conflict'] as String?);
                    };
                  },
                  onCommitted: () { setState(() => _saved = true); _finish(); },
                ),
                if (_finishError != null) Text(_finishError!),
              ]);
            },
          ),
        ),
      ),
    );
  }
}
