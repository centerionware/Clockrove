import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import '../maps/work_location_map.dart';
import 'tracking_api.dart';

class TrackingControls extends ConsumerStatefulWidget {
  const TrackingControls({super.key});
  @override
  ConsumerState<TrackingControls> createState() => _TrackingControlsState();
}

class _TrackingControlsState extends ConsumerState<TrackingControls> {
  bool _busy = false;
  AppMessage? _notice;

  Future<void> _enable() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        scrollable: true,
        title: Text(AppMessage.locationTitle.of(context)),
        content: Text(AppMessage.locationPurpose.of(context)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.cancel.of(context)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.continueLabel.of(context)),
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
        setState(() => _notice = AppMessage.locationDenied);
      }
    } catch (_) {
      if (mounted) setState(() => _notice = AppMessage.locationRestricted);
    } finally {
      if (mounted) {
        ref.invalidate(trackingStatusProvider);
        setState(() => _busy = false);
      }
    }
  }

  AppMessage _description(TrackingStatus status) {
    if (!status.working) return AppMessage.locationIdle;
    if (status.permission == 'denied') return AppMessage.locationDenied;
    return switch (status.service) {
      'REQUESTING' => AppMessage.locationRequesting,
      'START_REQUESTED' => AppMessage.locationStarting,
      'LOCATION_UNAVAILABLE' => AppMessage.locationUnavailable,
      'DISABLED' => AppMessage.locationRecordingDisabled,
      'PROVIDER_ERROR' || 'START_RESTRICTED' => AppMessage.locationRestricted,
      _ => AppMessage.locationStopped,
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
                  AppMessage.locationTitle.of(context),
                  style: Theme.of(context).textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.w600),
                ),
              ],
            ),
            const SizedBox(height: 10),
            status.when(
              loading: () => const LinearProgressIndicator(),
              error: (error, stack) =>
                  Text(AppMessage.locationRestricted.of(context)),
              data: (value) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_description(value).of(context)),
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
                    Text(AppMessage.locationApproximate.of(context)),
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
                            child: Text(AppMessage.enableLocation.of(context)),
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
                            child: Text(AppMessage.disableLocation.of(context)),
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
                            child: Text(
                              AppMessage.allowNotifications.of(context),
                            ),
                          ),
                      ],
                    ),
                ],
              ),
            ),
            if (_notice != null) Text(_notice!.of(context)),
            if (_busy) const LinearProgressIndicator(),
          ],
        ),
      ),
    );
  }
}
