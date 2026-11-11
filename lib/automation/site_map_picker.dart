import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../l10n/app_strings.dart';
import '../maps/tile_backoff_client.dart';
import '../maps/work_location_map.dart';

class SiteMapPicker extends ConsumerStatefulWidget {
  const SiteMapPicker({super.key, this.latitude, this.longitude, required this.radius});
  final double? latitude;
  final double? longitude;
  final double radius;
  @override
  ConsumerState<SiteMapPicker> createState() => _SiteMapPickerState();
}

class _SiteMapPickerState extends ConsumerState<SiteMapPicker> {
  final _controller = MapController();
  late final _client = TileBackoffClient(http.Client());
  late final TileProvider _tiles;
  LatLng? _point;
  bool _failed = false;
  @override
  void initState() {
    super.initState();
    _tiles = ref.read(workMapTilesProvider)(_client);
    final lat = widget.latitude;
    final lon = widget.longitude;
    if (lat != null && lat.isFinite && lat.abs() <= 85 && lon != null && lon.isFinite && lon.abs() <= 180) {
      _point = LatLng(lat, lon);
    }
  }
  @override
  void dispose() { _client.close(); _controller.dispose(); super.dispose(); }
  void _failure() { WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted && !_failed) { setState(() => _failed = true); } }); }
  Future<void> _attribution() async {
    try {
      if (await launchUrl(Uri.parse('https://www.openstreetmap.org/copyright'), mode: LaunchMode.externalApplication)) { return; }
    } catch (_) { /* Coordinate selection does not depend on a browser. */ }
    if (mounted) { ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('The attribution link could not be opened.'))); }
  }
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(appBar: AppBar(title: const Text('Choose site centre')),
      body: SafeArea(child: LayoutBuilder(builder: (context, constraints) => Column(children: [
        ConstrainedBox(constraints: BoxConstraints(maxHeight: constraints.maxHeight * 0.35), child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Text('Tap the map to choose the centre of your work site.'),
            const Text(AppStrings.mapPrivacy),
            Text('Radius: ${widget.radius.toStringAsFixed(0)} m. Accuracy and arrival/departure delivery can vary.'),
            if (widget.latitude != null && widget.latitude!.abs() > 85)
              const Text('This map cannot display polar locations. You can keep or edit those coordinates in the site form.'),
            if (_failed) const Padding(padding: EdgeInsets.only(top: 8), child: Text('Map tiles are unavailable. Go back to enter coordinates or use current location.')),
          ]))),
        Expanded(child: Stack(children: [
          FlutterMap(mapController: _controller, options: MapOptions(initialCenter: _point ?? const LatLng(0, 0),
            initialZoom: _point == null ? 2 : 15, minZoom: 2, maxZoom: 19, backgroundColor: colors.surfaceContainer,
            interactionOptions: const InteractionOptions(flags: InteractiveFlag.all & ~InteractiveFlag.rotate),
            onTap: (_, point) {
              if (_failed) return;
              final lon = ((point.longitude + 180) % 360 + 360) % 360 - 180;
              setState(() => _point = LatLng(point.latitude.clamp(-85, 85).toDouble(), lon));
            }), children: [
              TileLayer(urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', userAgentPackageName: 'io.github.centerionware.clockrove',
                tileProvider: _tiles, panBuffer: 0, maxNativeZoom: 19, errorTileCallback: (_, _, _) => _failure()),
              if (_point case final point?) CircleLayer(circles: [CircleMarker(point: point, radius: widget.radius, useRadiusInMeter: true,
                color: const Color(0xFF176B55).withValues(alpha: 0.15), borderColor: const Color(0xFF176B55), borderStrokeWidth: 2)]),
              if (_point case final point?) MarkerLayer(markers: [Marker(point: point, width: 48, height: 48,
                child: const Semantics(label: 'Selected site centre', child: Icon(Icons.location_on, color: Color(0xFF176B55), size: 40)))]),
            ]),
          Positioned(right: 16, top: 16, child: Column(children: [
            IconButton.filledTonal(tooltip: 'Zoom in', onPressed: () => _controller.move(_controller.camera.center, (_controller.camera.zoom + 1).clamp(2, 19).toDouble()), icon: const Icon(Icons.add)),
            const SizedBox(height: 8),
            IconButton.filledTonal(tooltip: 'Zoom out', onPressed: () => _controller.move(_controller.camera.center, (_controller.camera.zoom - 1).clamp(2, 19).toDouble()), icon: const Icon(Icons.remove)),
          ])),
        ])),
        ColoredBox(color: colors.surface, child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          TextButton(onPressed: _attribution, child: const Text('© OpenStreetMap contributors')),
          Padding(padding: const EdgeInsets.fromLTRB(20, 0, 20, 16), child: FilledButton(
            onPressed: _point == null || _failed ? null : () => Navigator.pop(context, _point), child: const Text('Use this centre'))),
        ])),
      ]))));
  }
}
