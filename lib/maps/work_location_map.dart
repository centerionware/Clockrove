import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../l10n/app_strings.dart';
import 'tile_backoff_client.dart';

/// A new provider per screen; tests supply synthetic tiles without public traffic.
final workMapTilesProvider = Provider<TileProvider Function(http.Client)>((ref) {
  return (client) => NetworkTileProvider(
    httpClient: client,
    headers: const {
      'User-Agent': 'Clockrove/0.1 (io.github.centerionware.clockrove; '
          '+https://github.com/centerionware/Clockrove)',
    },
    cachingProvider: BuiltInMapCachingProvider.getOrCreateInstance(
      maxCacheSize: 100000000,
    ),
    attemptDecodeOfHttpErrorResponses: false,
  );
});

class WorkLocationMap extends ConsumerStatefulWidget {
  const WorkLocationMap({
    super.key,
    required this.latitude,
    required this.longitude,
    required this.measuredUtc,
    this.accuracyMeters,
  });
  final double latitude;
  final double longitude;
  final DateTime measuredUtc;
  final double? accuracyMeters;

  @override
  ConsumerState<WorkLocationMap> createState() => _WorkLocationMapState();
}

class _WorkLocationMapState extends ConsumerState<WorkLocationMap> {
  late final TileBackoffClient _client;
  late final TileProvider _tiles;
  final _controller = MapController();
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _client = TileBackoffClient(http.Client());
    _tiles = ref.read(workMapTilesProvider)(_client);
  }

  @override
  void dispose() {
    _client.close();
    _controller.dispose();
    super.dispose();
  }

  void _tileFailed() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_failed) setState(() => _failed = true);
    });
  }

  Future<void> _attribution() async {
    try {
      final opened = await launchUrl(
        Uri.parse('https://www.openstreetmap.org/copyright'),
        mode: LaunchMode.externalApplication,
      );
      if (opened) return;
    } catch (_) {
      // Keep an unavailable browser separate from map and timer state.
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text(AppStrings.linkUnavailable)),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final point = LatLng(widget.latitude, widget.longitude);
    final colors = Theme.of(context).colorScheme;
    final measured = widget.measuredUtc.toLocal();
    return Scaffold(
      appBar: AppBar(title: const Text(AppStrings.workLocationMap)),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${AppStrings.lastSample} '
                    '${MaterialLocalizations.of(context).formatShortDate(measured)} '
                    '${MaterialLocalizations.of(context).formatTimeOfDay(TimeOfDay.fromDateTime(measured))}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (widget.accuracyMeters != null)
                    Text('${widget.accuracyMeters!.toStringAsFixed(0)} m ${AppStrings.accuracy}'),
                  const SizedBox(height: 6),
                  const Text(AppStrings.mapPrivacy),
                ],
              ),
            ),
            if (_failed)
              Container(
                color: colors.errorContainer,
                padding: const EdgeInsets.all(16),
                child: Text(AppStrings.mapUnavailable, style: TextStyle(color: colors.onErrorContainer)),
              ),
            Expanded(
              child: Stack(
                children: [
                  FlutterMap(
                    mapController: _controller,
                    options: MapOptions(
                      initialCenter: point,
                      initialZoom: 15,
                      minZoom: 2,
                      maxZoom: 19,
                      backgroundColor: colors.surfaceContainer,
                      interactionOptions: const InteractionOptions(
                        flags: InteractiveFlag.all & ~InteractiveFlag.rotate,
                      ),
                    ),
                    children: [
                      TileLayer(
                        urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                        userAgentPackageName: 'io.github.centerionware.clockrove',
                        tileProvider: _tiles,
                        panBuffer: 0,
                        maxNativeZoom: 19,
                        errorTileCallback: (_, _, _) => _tileFailed(),
                      ),
                      if (widget.accuracyMeters case final accuracy?)
                        CircleLayer(circles: [
                          CircleMarker(
                            point: point,
                            radius: accuracy,
                            useRadiusInMeter: true,
                            color: colors.primary.withValues(alpha: 0.12),
                            borderColor: colors.primary.withValues(alpha: 0.5),
                            borderStrokeWidth: 1,
                          ),
                        ]),
                      MarkerLayer(markers: [
                        Marker(
                          point: point,
                          width: 48,
                          height: 48,
                          child: Semantics(
                            label: AppStrings.recordedLocation,
                            child: Icon(Icons.location_on_rounded, color: colors.primary, size: 40),
                          ),
                        ),
                      ]),
                    ],
                  ),
                  Positioned(
                    right: 16,
                    top: 16,
                    child: Column(children: [
                      IconButton.filledTonal(
                        tooltip: AppStrings.zoomIn,
                        onPressed: () => _controller.move(_controller.camera.center, (_controller.camera.zoom + 1).clamp(2, 19).toDouble()),
                        icon: const Icon(Icons.add),
                      ),
                      const SizedBox(height: 8),
                      IconButton.filledTonal(
                        tooltip: AppStrings.zoomOut,
                        onPressed: () => _controller.move(_controller.camera.center, (_controller.camera.zoom - 1).clamp(2, 19).toDouble()),
                        icon: const Icon(Icons.remove),
                      ),
                    ]),
                  ),
                ],
              ),
            ),
            // Attribution always stays visible, including when tiles fail.
            ColoredBox(
              color: colors.surface,
              child: TextButton(onPressed: _attribution, child: const Text('© OpenStreetMap contributors')),
            ),
          ],
        ),
      ),
    );
  }
}
