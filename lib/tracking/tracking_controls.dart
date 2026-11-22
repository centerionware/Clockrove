import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import '../l10n/app_strings.dart';
import '../maps/work_location_map.dart';
import 'tracking_api.dart';

class TrackingControls extends ConsumerStatefulWidget {
  const TrackingControls({super.key});
  @override
  ConsumerState<TrackingControls> createState() => _TrackingControlsState();
}

class _TrackingControlsState extends ConsumerState<TrackingControls> {
  bool _busy = false;
  String? _notice;

  Future<void> _enable() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text(AppStrings.locationTitle),
        content: const Text(AppStrings.locationPurpose),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.cancel.of(context)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text(AppStrings.continueLabel),
          ),
        ],
      ),
    );
    if (accepted == true && mounted) {
      await _run(() => ref.read(trackingApiProvider).enable());
    }
  }

  Future<void> _run(Future<TrackingStatus> Function() action) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _notice = null;
    });
    try {
      final status = await action();
      if (!mounted) return;
      if (status.permission == 'denied') {
        setState(() => _notice = AppStrings.locationDenied);
      }
    } catch (_) {
      if (mounted) setState(() => _notice = AppStrings.locationRestricted);
    } finally {
      if (mounted) {
        ref.invalidate(trackingStatusProvider);
        setState(() => _busy = false);
      }
    }
  }

  String _description(TrackingStatus status) {
    if (!status.working) return AppStrings.locationIdle;
    if (status.permission == 'denied') return AppStrings.locationDenied;
    return switch (status.service) {
      'REQUESTING' => AppStrings.locationRequesting,
      'START_REQUESTED' => AppStrings.locationStarting,
      'LOCATION_UNAVAILABLE' => AppStrings.locationUnavailable,
      'DISABLED' => AppStrings.locationRecordingDisabled,
      'PROVIDER_ERROR' || 'START_RESTRICTED' => AppStrings.locationRestricted,
      _ => AppStrings.locationStopped,
    };
  }

  String _sampleLabel(
    BuildContext context,
    DateTime observation,
    double? meters,
  ) {
    final local = observation.toLocal();
    final material = MaterialLocalizations.of(context);
    final sample = AppMessage.lastLocationSample.format(context, {
      'date': material.formatShortDate(local),
      'time': material.formatTimeOfDay(TimeOfDay.fromDateTime(local)),
    });
    if (meters == null) return sample;
    final accuracy = AppMessage.locationAccuracy.format(context, {
      'meters': meters.toStringAsFixed(0),
    });
    return '$sample · $accuracy';
  }

  @override
  Widget build(BuildContext context) {
    final status = ref.watch(trackingStatusProvider);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.location_on_outlined,
                  size: 22,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 10),
                Text(
                  AppStrings.locationTitle,
                  style: Theme.of(context).textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.w600),
                ),
              ],
            ),
            const SizedBox(height: 10),
            status.when(
              loading: () => const LinearProgressIndicator(),
              error: (error, stack) =>
                  const Text(AppStrings.locationRestricted),
              data: (value) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_description(value)),
                  if (value.lastObservationUtc case final observation?)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(
                        _sampleLabel(
                          context,
                          observation,
                          value.lastAccuracyMeters,
                        ),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                  if (value.hasMapObservation)
                    TextButton.icon(
                      onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => WorkLocationMap(
                            latitude: value.lastLatitude!,
                            longitude: value.lastLongitude!,
                            measuredUtc: value.lastObservationUtc!,
                            accuracyMeters: value.lastAccuracyMeters,
                          ),
                        ),
                      ),
                      icon: const Icon(Icons.map_outlined),
                      label: Text(AppMessage.viewLocationMap.of(context)),
                    ),
                  if (value.permission == 'approximate')
                    const Text(AppStrings.locationApproximate),
                  if (value.working)
                    Wrap(
                      spacing: 8,
                      children: [
                        if (value.service != 'DISABLED' &&
                            !const {
                              'REQUESTING',
                              'START_REQUESTED',
                              'LOCATION_UNAVAILABLE',
                            }.contains(value.service))
                          TextButton(
                            onPressed: _busy ? null : _enable,
                            child: const Text(AppStrings.enableLocation),
                          ),
                        if (const {
                          'REQUESTING',
                          'START_REQUESTED',
                          'LOCATION_UNAVAILABLE',
                        }.contains(value.service))
                          TextButton(
                            onPressed: _busy
                                ? null
                                : () => _run(
                                    () =>
                                        ref.read(trackingApiProvider).disable(),
                                  ),
                            child: const Text(AppStrings.disableLocation),
                          ),
                        if (!value.notificationsAllowed)
                          TextButton(
                            onPressed: _busy
                                ? null
                                : () => _run(
                                    () => ref
                                        .read(trackingApiProvider)
                                        .requestNotifications(),
                                  ),
                            child: const Text(AppStrings.allowNotifications),
                          ),
                      ],
                    ),
                ],
              ),
            ),
            if (_notice != null) Text(_notice!),
            if (_busy) const LinearProgressIndicator(),
          ],
        ),
      ),
    );
  }
}
