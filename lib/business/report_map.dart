import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../l10n/app_localizations.dart';
import '../maps/map_provider_settings.dart';
import '../maps/tile_backoff_client.dart';
import '../maps/work_location_map.dart';
import 'report_api.dart';

bool _isMapPoint(ReportPoint point) =>
    point.latitude.isFinite &&
    point.longitude.isFinite &&
    point.latitude.abs() <= 85.05112878 &&
    point.longitude >= -180 &&
    point.longitude <= 180;

ReportPoint? _firstMapPoint(Iterable<ReportPoint> points) {
  for (final point in points) {
    if (_isMapPoint(point)) return point;
  }
  return null;
}

/// Trace breaks at session boundaries, gaps over 30 minutes and the antimeridian.
List<List<ReportPoint>> reportSegments(List<ReportPoint> points) {
  final sessions = <String, List<ReportPoint>>{};
  for (final point in points) {
    sessions.putIfAbsent(point.sessionId, () => []).add(point);
  }
  final segments = <List<ReportPoint>>[];
  for (final samples in sessions.values) {
    samples.sort((a, b) => a.measured.compareTo(b.measured));
    var current = <ReportPoint>[];
    for (final point in samples) {
      if (!_isMapPoint(point)) {
        if (current.length > 1) {
          segments.add(current);
        }
        current = [];
        continue;
      }
      if (current.isNotEmpty &&
          (point.measured - current.last.measured > 1800000 ||
              (point.longitude - current.last.longitude).abs() > 180)) {
        if (current.length > 1) {
          segments.add(current);
        }
        current = [];
      }
      current.add(point);
    }
    if (current.length > 1) {
      segments.add(current);
    }
  }
  return segments;
}

class ReportMap extends ConsumerStatefulWidget {
  const ReportMap({super.key, required this.points, required this.timezone});
  final List<ReportPoint> points;
  final String timezone;
  @override
  ConsumerState<ReportMap> createState() => _ReportMapState();
}

class _ReportMapState extends ConsumerState<ReportMap> {
  late final _client = TileBackoffClient(
    http.Client(),
    protectedHeaders: () =>
        ref.read(mapProviderSettingsApiProvider).tileAuthHeaders(),
  );
  MapProviderSettings? _tileSettings;
  TileProvider? _tiles;
  final _controller = MapController();
  late ReportPoint? _selected = _firstMapPoint(widget.points);
  bool _failed = false;
  bool _loaded = false;

  TileProvider _tilesFor(MapProviderSettings settings) {
    if (!identical(_tileSettings, settings)) {
      _tileSettings = settings;
      _client.resetProtectedHeaders();
      _failed = false;
      _loaded = false;
      _tiles = ref.read(workMapTilesProvider)(_client, settings);
    }
    return _tiles!;
  }

  @override
  void dispose() {
    _client.close();
    _controller.dispose();
    super.dispose();
  }

  void _failure() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_failed) setState(() => _failed = true);
    });
  }

  void _tileUpdated(TileImage tile) {
    if (!tile.readyToDisplay) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (tile.loadError && !_failed) {
        setState(() => _failed = true);
      } else if (!tile.loadError && !_loaded) {
        setState(() => _loaded = true);
      }
    });
  }

  void _select(ReportPoint point) {
    setState(() => _selected = point);
    if (_isMapPoint(point)) {
      _controller.move(
        LatLng(point.latitude, point.longitude),
        _controller.camera.zoom,
      );
    }
  }

  Future<void> _openExternalMap() async {
    final point = _selected;
    if (point == null || !_isMapPoint(point)) return;
    final latitude = point.latitude.toStringAsFixed(6);
    final longitude = point.longitude.toStringAsFixed(6);
    final webMap = Uri.https('www.openstreetmap.org', '/', {
      'mlat': latitude,
      'mlon': longitude,
    }).replace(fragment: 'map=17/$latitude/$longitude');
    for (final uri in [
      Uri(
        scheme: 'geo',
        path: '$latitude,$longitude',
        queryParameters: {'q': '$latitude,$longitude'},
      ),
      webMap,
    ]) {
      try {
        if (await launchUrl(uri, mode: LaunchMode.externalApplication)) return;
      } catch (_) {
        // Map launch failures do not affect the stored GPS history.
      }
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppMessage.mapNoExternalApp.of(context))),
      );
    }
  }

  Future<void> _attribution(MapProviderSettings settings) async {
    if (settings.attributionUrl.isEmpty) return;
    try {
      if (await launchUrl(
        Uri.parse(settings.attributionUrl),
        mode: LaunchMode.externalApplication,
      )) {
        return;
      }
    } catch (_) {}
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(AppMessage.mapAttributionLinkFailed.of(context)),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final settingsState = ref.watch(mapProviderSettingsProvider);
    if (settingsState.isLoading) {
      return Scaffold(
        appBar: AppBar(title: Text(AppMessage.reportLocationHistory.of(context))),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    if (settingsState.hasError) {
      return Scaffold(
        appBar: AppBar(title: Text(AppMessage.reportLocationHistory.of(context))),
        body: Center(
          child: TextButton(
            onPressed: () => ref.invalidate(mapProviderSettingsProvider),
            child: Text(AppMessage.mapSettingsRetry.of(context)),
          ),
        ),
      );
    }
    final mapSettings = settingsState.requireValue;
    final tiles = _tilesFor(mapSettings);
    final visible = widget.points.where(_isMapPoint).toList();
    final center = visible.isEmpty
        ? const LatLng(0, 0)
        : LatLng(visible.first.latitude, visible.first.longitude);
    final selected = _selected;
    const ink = Color(0xff176b55);
    return Scaffold(
      appBar: AppBar(title: Text(AppMessage.reportLocationHistory.of(context))),
      body: SafeArea(
        child: Column(
          children: [
            ConstrainedBox(
              constraints: BoxConstraints(
                maxHeight: MediaQuery.sizeOf(context).height * .3,
              ),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (selected != null) ...[
                      Text(
                        selected.name,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      Text(
                        '${selected.latitude.toStringAsFixed(6)}, ${selected.longitude.toStringAsFixed(6)}',
                      ),
                      Text(
                        [
                          selected.label,
                          AppMessage.locationAccuracy.format(context, {
                            'meters': selected.accuracy.toStringAsFixed(0),
                          }),
                        ].join(' · '),
                      ),
                    ] else
                      Text(AppMessage.reportMapNoSamples.of(context)),
                    Text(
                      AppMessage.reportMapTimezone.format(context, {
                        'timezone': widget.timezone,
                      }),
                    ),
                    Text(AppMessage.reportMapSampleLines.of(context)),
                    if (visible.length != widget.points.length)
                      Text(AppMessage.reportMapOutsideRange.of(context)),
                  ],
                ),
              ),
            ),
            Expanded(
              child: Stack(
                children: [
                  FlutterMap(
                    mapController: _controller,
                    options: MapOptions(
                      initialCenter: center,
                      initialZoom: visible.isEmpty ? 2 : 15,
                      minZoom: 2,
                      maxZoom: 19,
                      backgroundColor: Theme.of(context)
                          .colorScheme
                          .surfaceContainer,
                      interactionOptions: const InteractionOptions(
                        flags: InteractiveFlag.all & ~InteractiveFlag.rotate,
                      ),
                    ),
                    children: [
                      TileLayer(
                        urlTemplate: mapSettings.urlTemplate,
                        userAgentPackageName:
                            'io.github.centerionware.clockrove',
                        tileProvider: tiles,
                        maxNativeZoom: 19,
                        tileBuilder: (context, image, tile) {
                          _tileUpdated(tile);
                          return workMapTileContent(context, image, tile);
                        },
                        errorTileCallback: (_, _, _) => _failure(),
                      ),
                      PolylineLayer(
                        polylines: [
                          for (final segment in reportSegments(widget.points))
                            Polyline(
                              points: segment
                                  .map((p) => LatLng(p.latitude, p.longitude))
                                  .toList(),
                              color: ink.withValues(alpha: .6),
                              strokeWidth: 3,
                            ),
                        ],
                      ),
                      if (selected != null &&
                          selected.latitude.abs() <= 85.05112878)
                        CircleLayer(
                          circles: [
                            CircleMarker(
                              point: LatLng(
                                selected.latitude,
                                selected.longitude,
                              ),
                              radius: selected.accuracy,
                              useRadiusInMeter: true,
                              color: ink.withValues(alpha: .1),
                              borderColor: ink,
                              borderStrokeWidth: 1,
                            ),
                          ],
                        ),
                      MarkerLayer(
                        markers: [
                          for (final point in visible)
                            Marker(
                              point: LatLng(point.latitude, point.longitude),
                              width: 48,
                              height: 48,
                              child: IconButton(
                                tooltip: '${point.name} · ${point.label}',
                                onPressed: () => _select(point),
                                icon: Icon(
                                  point.id == selected?.id
                                      ? Icons.location_on
                                      : Icons.circle,
                                  color: ink,
                                  size: point.id == selected?.id ? 36 : 14,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ],
                  ),
                  Positioned(
                    left: 12,
                    top: 12,
                    right: 72,
                    child: WorkMapTileStatus(
                      loading: !_loaded && !_failed,
                      failed: _failed,
                      onOpenMap: _openExternalMap,
                    ),
                  ),
                  Positioned(
                    right: 12,
                    top: 12,
                    child: Card(
                      child: Column(
                        children: [
                          IconButton(
                            tooltip: AppMessage.zoomIn.of(context),
                            onPressed: () => _controller.move(
                              _controller.camera.center,
                              (_controller.camera.zoom + 1).clamp(2, 19),
                            ),
                            icon: const Icon(Icons.add),
                          ),
                          IconButton(
                            tooltip: AppMessage.zoomOut.of(context),
                            onPressed: () => _controller.move(
                              _controller.camera.center,
                              (_controller.camera.zoom - 1).clamp(2, 19),
                            ),
                            icon: const Icon(Icons.remove),
                          ),
                        ],
                      ),
                    ),
                  ),
                  Positioned(
                    left: 12,
                    bottom: 12,
                    child: Material(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(8),
                      child: TextButton(
                        onPressed: () => _attribution(mapSettings),
                        child: Text(
                          mapSettings.attribution,
                          style: const TextStyle(color: ink),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            SizedBox(
              height: MediaQuery.textScalerOf(context).scale(72).clamp(96, 170),
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.all(12),
                children: [
                  for (final point in widget.points)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: ActionChip(
                        label: Text(
                          [
                          point.label,
                          AppMessage.locationAccuracy.format(context, {
                            'meters': point.accuracy.toStringAsFixed(0),
                          }),
                        ].join('\n'),
                        ),
                        onPressed: () => _select(point),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
