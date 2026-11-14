import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../l10n/app_strings.dart';
import 'tile_backoff_client.dart';

/// A new provider per screen; tests supply synthetic tiles without public traffic.
final workMapTilesProvider = Provider<TileProvider Function(http.Client)>((
  ref,
) {
  return (client) => NetworkTileProvider(
    httpClient: client,
    headers: const {
      'User-Agent':
          'Clockrove/0.1 (io.github.centerionware.clockrove; '
          '+https://github.com/centerionware/Clockrove)',
    },
    cachingProvider: BuiltInMapCachingProvider.getOrCreateInstance(
      maxCacheSize: 100000000,
    ),
    attemptDecodeOfHttpErrorResponses: false,
  );
});

/// A failed network tile must leave a visible map surface instead of exposing
/// the blank map background. The point/trace overlays remain interactive.
Widget workMapTileContent(BuildContext context, Widget image, TileImage tile) {
  if (!tile.loadError) return image;
  final colors = Theme.of(context).colorScheme;
  return DecoratedBox(
    decoration: BoxDecoration(
      color: colors.surfaceContainerHigh,
      border: Border.all(color: colors.outlineVariant, width: 0.5),
    ),
    child: Center(
      child: Icon(Icons.map_outlined, color: colors.primary.withValues(alpha: 0.7)),
    ),
  );
}

class WorkMapTileStatus extends StatelessWidget {
  const WorkMapTileStatus({
    super.key,
    required this.loading,
    required this.failed,
    required this.onOpenMap,
  });

  final bool loading;
  final bool failed;
  final VoidCallback onOpenMap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final label = failed
        ? AppStrings.mapUnavailable
        : loading
        ? 'Loading map tiles…'
        : 'Map tiles loaded';
    return Card(
      color: failed ? colors.errorContainer : colors.surface,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              failed ? Icons.warning_amber_rounded : Icons.map_outlined,
              size: 18,
              color: failed ? colors.onErrorContainer : colors.primary,
            ),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                label,
                style: TextStyle(
                  color: failed ? colors.onErrorContainer : colors.onSurface,
                ),
              ),
            ),
            TextButton.icon(
              onPressed: onOpenMap,
              icon: const Icon(Icons.open_in_new),
              label: const Text('Open map'),
            ),
          ],
        ),
      ),
    );
  }
}

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
  bool _loaded = false;

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
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text(AppStrings.linkUnavailable)));
    }
  }

  Future<void> _openExternalMap() async {
    final latitude = widget.latitude.toStringAsFixed(6);
    final longitude = widget.longitude.toStringAsFixed(6);
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
        // Keep an unavailable map app/browser separate from location history.
      }
    }
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text(AppStrings.linkUnavailable)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final point = LatLng(widget.latitude, widget.longitude);
    const mapInk = Color(0xFF176B55);
    final colors = Theme.of(context).colorScheme;
    final measured = widget.measuredUtc.toLocal();
    return Scaffold(
      appBar: AppBar(title: const Text(AppStrings.workLocationMap)),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: constraints.maxHeight * 0.4,
                ),
                child: SingleChildScrollView(
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
                            Text(
                              '${widget.latitude.toStringAsFixed(6)}, ${widget.longitude.toStringAsFixed(6)}',
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                            if (widget.accuracyMeters != null)
                              Text(
                                '${widget.accuracyMeters!.toStringAsFixed(0)} m ${AppStrings.accuracy}',
                              ),
                            const SizedBox(height: 6),
                            const Text(AppStrings.mapPrivacy),
                          ],
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
                          urlTemplate:
                              'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                          userAgentPackageName:
                              'io.github.centerionware.clockrove',
                          tileProvider: _tiles,
                          panBuffer: 0,
                          maxNativeZoom: 19,
                          tileBuilder: (context, image, tile) {
                            _tileUpdated(tile);
                            return workMapTileContent(context, image, tile);
                          },
                          errorTileCallback: (_, _, _) => _tileFailed(),
                        ),
                        if (widget.accuracyMeters case final accuracy?)
                          CircleLayer(
                            circles: [
                              CircleMarker(
                                point: point,
                                radius: accuracy,
                                useRadiusInMeter: true,
                                color: mapInk.withValues(alpha: 0.12),
                                borderColor: mapInk.withValues(alpha: 0.6),
                                borderStrokeWidth: 1,
                              ),
                            ],
                          ),
                        MarkerLayer(
                          markers: [
                            Marker(
                              point: point,
                              width: 48,
                              height: 48,
                              child: Semantics(
                                label: AppStrings.recordedLocation,
                                child: const DecoratedBox(
                                  decoration: BoxDecoration(
                                    color: Colors.white,
                                    shape: BoxShape.circle,
                                  ),
                                  child: Icon(
                                    Icons.my_location_rounded,
                                    color: mapInk,
                                    size: 30,
                                  ),
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
                      right: 16,
                      top: 16,
                      child: Flex(
                        direction: constraints.maxHeight < 400
                            ? Axis.horizontal
                            : Axis.vertical,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton.filledTonal(
                            tooltip: AppStrings.zoomIn,
                            onPressed: () => _controller.move(
                              _controller.camera.center,
                              (_controller.camera.zoom + 1)
                                  .clamp(2, 19)
                                  .toDouble(),
                            ),
                            icon: const Icon(Icons.add),
                          ),
                          const SizedBox(width: 8, height: 8),
                          IconButton.filledTonal(
                            tooltip: AppStrings.zoomOut,
                            onPressed: () => _controller.move(
                              _controller.camera.center,
                              (_controller.camera.zoom - 1)
                                  .clamp(2, 19)
                                  .toDouble(),
                            ),
                            icon: const Icon(Icons.remove),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              // Attribution always stays visible, including when tiles fail.
              ColoredBox(
                color: colors.surface,
                child: TextButton(
                  onPressed: _attribution,
                  child: const Text('© OpenStreetMap contributors'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
