import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../maps/tile_backoff_client.dart';
import '../maps/work_location_map.dart';
import 'report_api.dart';

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
      if (point.latitude.abs() > 85.05112878) {
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
  late final _client = TileBackoffClient(http.Client());
  late final _tiles = ref.read(workMapTilesProvider)(_client);
  final _controller = MapController();
  late ReportPoint? _selected =
      widget.points.isEmpty ? null : widget.points.first;
  bool _failed = false;
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

  void _select(ReportPoint point) {
    setState(() => _selected = point);
    if (point.latitude.abs() <= 85.05112878) {
      _controller.move(
        LatLng(point.latitude, point.longitude),
        _controller.camera.zoom,
      );
    }
  }

  Future<void> _attribution() async {
    try {
      if (await launchUrl(
        Uri.parse('https://www.openstreetmap.org/copyright'),
        mode: LaunchMode.externalApplication,
      )) {
        return;
      }
    } catch (_) {}
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('OpenStreetMap attribution link could not be opened.'),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final visible = widget.points
        .where((p) => p.latitude.abs() <= 85.05112878)
        .toList();
    final center = visible.isEmpty
        ? const LatLng(0, 0)
        : LatLng(visible.first.latitude, visible.first.longitude);
    final selected = _selected;
    const ink = Color(0xff176b55);
    return Scaffold(
      appBar: AppBar(title: const Text('Report location history')),
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
                        '${selected.label} · ${selected.accuracy.toStringAsFixed(0)} m accuracy',
                      ),
                    ] else
                      const Text('No recorded location samples in this report.'),
                    Text('Report timezone: ${widget.timezone}'),
                    const Text(
                      'Lines connect measured samples within a session, with gaps over 30 minutes left open. They are not verified routes. Map requests share the viewed area with the tile provider.',
                    ),
                    if (visible.length != widget.points.length)
                      const Text(
                        'Polar samples remain in the list and CSV; this map cannot display them.',
                      ),
                    if (_failed)
                      Text(
                        'Map tiles are unavailable. Reports and exports still work.',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
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
                        urlTemplate:
                            'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                        userAgentPackageName:
                            'io.github.centerionware.clockrove',
                        tileProvider: _tiles,
                        maxNativeZoom: 19,
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
                    right: 12,
                    top: 12,
                    child: Card(
                      child: Column(
                        children: [
                          IconButton(
                            tooltip: 'Zoom in',
                            onPressed: () => _controller.move(
                              _controller.camera.center,
                              (_controller.camera.zoom + 1).clamp(2, 19),
                            ),
                            icon: const Icon(Icons.add),
                          ),
                          IconButton(
                            tooltip: 'Zoom out',
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
                        onPressed: _attribution,
                        child: const Text(
                          '© OpenStreetMap contributors',
                          style: TextStyle(color: ink),
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
                          '${point.label}\n${point.accuracy.toStringAsFixed(0)} m accuracy',
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
