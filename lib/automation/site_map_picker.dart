import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../l10n/app_localizations.dart';
import '../maps/address_search.dart';
import '../maps/map_provider_settings.dart';
import '../maps/tile_backoff_client.dart';
import '../maps/work_location_map.dart';

class SiteMapPicker extends ConsumerStatefulWidget {
  const SiteMapPicker({
    super.key,
    this.latitude,
    this.longitude,
    required this.radius,
  });
  final double? latitude;
  final double? longitude;
  final double radius;
  @override
  ConsumerState<SiteMapPicker> createState() => _SiteMapPickerState();
}

class _SiteMapPickerState extends ConsumerState<SiteMapPicker> {
  final _controller = MapController();
  late final _client = TileBackoffClient(
    http.Client(),
    protectedHeaders: () =>
        ref.read(mapProviderSettingsApiProvider).tileAuthHeaders(),
  );
  final _search = TextEditingController();
  MapProviderSettings? _tileSettings;
  TileProvider? _tiles;
  LatLng? _point;
  bool _failed = false;
  bool _searching = false;
  int _searchToken = 0;
  List<AddressSearchResult>? _searchResults;
  AppMessage? _searchNotice;
  @override
  void initState() {
    super.initState();
    final lat = widget.latitude;
    final lon = widget.longitude;
    if (lat != null &&
        lat.isFinite &&
        lat.abs() <= 85 &&
        lon != null &&
        lon.isFinite &&
        lon.abs() <= 180) {
      _point = LatLng(lat, lon);
    }
  }

  TileProvider _tilesFor(MapProviderSettings settings) {
    if (!identical(_tileSettings, settings)) {
      _tileSettings = settings;
      _client.resetProtectedHeaders();
      _failed = false;
      _tiles = ref.read(workMapTilesProvider)(_client, settings);
    }
    return _tiles!;
  }

  @override
  void dispose() {
    _client.close();
    _controller.dispose();
    _search.dispose();
    super.dispose();
  }

  void _onSearchChanged(String _) {
    _searchToken++;
    setState(() {
      _searching = false;
      _searchResults = null;
      _searchNotice = null;
    });
  }

  Future<void> _searchAddress() async {
    final query = _search.text.trim();
    if (query.length < 3) {
      setState(() {
        _searchResults = null;
        _searchNotice = AppMessage.siteAddressSearchTooShort;
      });
      return;
    }
    FocusScope.of(context).unfocus();
    final token = ++_searchToken;
    setState(() {
      _searching = true;
      _searchResults = null;
      _searchNotice = null;
    });
    try {
      final results = await ref
          .read(addressSearchApiProvider)
          .search(
            query,
            languageTag: Localizations.localeOf(context).toLanguageTag(),
          );
      if (!mounted || token != _searchToken) return;
      setState(() {
        _searchResults = results;
        _searchNotice = results.isEmpty
            ? AppMessage.siteAddressSearchEmpty
            : null;
      });
    } on AddressSearchException catch (error) {
      if (!mounted || token != _searchToken) return;
      setState(() {
        _searchResults = null;
        _searchNotice = switch (error.failure) {
          AddressSearchFailure.tooShort => AppMessage.siteAddressSearchTooShort,
          AddressSearchFailure.rateLimited => AppMessage.siteAddressSearchBusy,
          AddressSearchFailure.unavailable =>
            AppMessage.siteAddressSearchFailed,
        };
      });
    } catch (_) {
      if (!mounted || token != _searchToken) return;
      setState(() {
        _searchResults = null;
        _searchNotice = AppMessage.siteAddressSearchFailed;
      });
    } finally {
      if (mounted && token == _searchToken) {
        setState(() => _searching = false);
      }
    }
  }

  void _chooseAddress(AddressSearchResult result) {
    // The map cannot render polar centres even though Nominatim may return them.
    final point = LatLng(result.latitude.clamp(-85.0, 85.0), result.longitude);
    FocusScope.of(context).unfocus();
    setState(() => _point = point);
    _controller.move(point, 16);
  }

  Future<void> _openSearchAttribution() async {
    try {
      if (await launchUrl(
        Uri.parse(defaultMapAttributionUrl),
        mode: LaunchMode.externalApplication,
      )) {
        return;
      }
    } catch (_) {
      /* Address search remains usable without a browser. */
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(AppMessage.mapAttributionLinkFailed.of(context)),
        ),
      );
    }
  }

  void _failure() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_failed) {
        setState(() => _failed = true);
      }
    });
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
    } catch (_) {
      /* Coordinate selection does not depend on a browser. */
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(AppMessage.mapAttributionLinkFailed.of(context)),
        ),
      );
    }
  }

  Future<void> _openExternalMap() async {
    final point = _point;
    if (point == null) return;
    final latitude = point.latitude.toStringAsFixed(6);
    final longitude = point.longitude.toStringAsFixed(6);
    final uri = Uri.https('www.openstreetmap.org', '/', {
      'mlat': latitude,
      'mlon': longitude,
    }).replace(fragment: 'map=17/$latitude/$longitude');
    try {
      if (await launchUrl(uri, mode: LaunchMode.externalApplication)) return;
    } catch (_) {
      // Site coordinates remain editable if no browser is available.
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppMessage.mapNoExternalApp.of(context))),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final settingsState = ref.watch(mapProviderSettingsProvider);
    if (settingsState.isLoading) {
      return Scaffold(
        appBar: AppBar(title: Text(AppMessage.chooseSiteCentre.of(context))),
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    if (settingsState.hasError) {
      return Scaffold(
        appBar: AppBar(title: Text(AppMessage.chooseSiteCentre.of(context))),
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
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: Text(AppMessage.chooseSiteCentre.of(context))),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) => Column(
            children: [
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: constraints.maxHeight * 0.35,
                ),
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(AppMessage.siteMapIntro.of(context)),
                      const SizedBox(height: 12),
                      TextField(
                        key: const ValueKey('site-address-search'),
                        controller: _search,
                        enabled: !_searching,
                        autocorrect: false,
                        textInputAction: TextInputAction.search,
                        onChanged: _onSearchChanged,
                        onSubmitted: (_) => _searchAddress(),
                        decoration: InputDecoration(
                          labelText: AppMessage.siteAddressSearch.of(context),
                          hintText: AppMessage.siteAddressSearchHint.of(
                            context,
                          ),
                          suffixIcon: IconButton(
                            tooltip: AppMessage.siteAddressSearchButton.of(
                              context,
                            ),
                            onPressed: _searching ? null : _searchAddress,
                            icon: const Icon(Icons.search),
                          ),
                        ),
                      ),
                      if (_searching) ...[
                        const SizedBox(height: 8),
                        LinearProgressIndicator(
                          semanticsLabel: AppMessage.siteAddressSearching.of(
                            context,
                          ),
                        ),
                      ],
                      if (_searchNotice case final notice?) ...[
                        const SizedBox(height: 8),
                        Text(notice.of(context)),
                      ],
                      if (_searchResults != null &&
                          _searchResults!.isNotEmpty) ...[
                        const SizedBox(height: 8),
                        Card(
                          margin: EdgeInsets.zero,
                          clipBehavior: Clip.antiAlias,
                          child: Column(
                            children: [
                              for (final result in _searchResults!)
                                ListTile(
                                  leading: const Icon(Icons.place_outlined),
                                  title: Text(result.displayName),
                                  onTap: () => _chooseAddress(result),
                                ),
                            ],
                          ),
                        ),
                      ],
                      const SizedBox(height: 4),
                      Text(
                        AppMessage.siteAddressSearchPrivacy.of(context),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: TextButton(
                          onPressed: _openSearchAttribution,
                          child: Text(
                            AppMessage.siteAddressSearchAttribution.of(context),
                          ),
                        ),
                      ),
                      Text(AppMessage.mapPrivacy.of(context)),
                      Text(
                        AppMessage.siteMapRadiusHelp.format(context, {
                          'radius': widget.radius.toStringAsFixed(
                            widget.radius < 10 ? 1 : 0,
                          ),
                        }),
                      ),
                      if (widget.latitude != null &&
                          widget.latitude!.abs() > 85)
                        Text(AppMessage.siteMapPolarHelp.of(context)),
                      if (_failed)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: Wrap(
                            crossAxisAlignment: WrapCrossAlignment.center,
                            children: [
                              Text(
                                AppMessage.siteMapTilesUnavailable.of(context),
                              ),
                              if (_point != null)
                                TextButton.icon(
                                  onPressed: _openExternalMap,
                                  icon: const Icon(Icons.open_in_new),
                                  label: Text(AppMessage.openMap.of(context)),
                                ),
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
                        initialCenter: _point ?? const LatLng(0, 0),
                        initialZoom: _point == null ? 2 : 15,
                        minZoom: 2,
                        maxZoom: 19,
                        backgroundColor: colors.surfaceContainer,
                        interactionOptions: const InteractionOptions(
                          flags: InteractiveFlag.all & ~InteractiveFlag.rotate,
                        ),
                        onTap: (_, point) {
                          if (_failed) return;
                          final lon =
                              ((point.longitude + 180) % 360 + 360) % 360 - 180;
                          setState(
                            () => _point = LatLng(
                              point.latitude.clamp(-85, 85).toDouble(),
                              lon,
                            ),
                          );
                        },
                      ),
                      children: [
                        TileLayer(
                          urlTemplate: mapSettings.urlTemplate,
                          userAgentPackageName:
                              'io.github.centerionware.clockrove',
                          tileProvider: tiles,
                          panBuffer: 0,
                          maxNativeZoom: 19,
                          errorTileCallback: (_, _, _) => _failure(),
                        ),
                        if (_point case final point?)
                          CircleLayer(
                            circles: [
                              CircleMarker(
                                point: point,
                                radius: widget.radius,
                                useRadiusInMeter: true,
                                color: Colors.transparent,
                                borderColor: Colors.white,
                                borderStrokeWidth: 5,
                              ),
                              CircleMarker(
                                point: point,
                                radius: widget.radius,
                                useRadiusInMeter: true,
                                color: const Color(0xFF176B55)
                                    .withValues(alpha: 0.15),
                                borderColor: const Color(0xFF176B55),
                                borderStrokeWidth: 2,
                              ),
                            ],
                          ),
                        if (_point case final point?)
                          MarkerLayer(
                            markers: [
                              Marker(
                                point: point,
                                width: 48,
                                height: 48,
                                child: Semantics(
                                  label: AppMessage.siteMapSelectedCentre.of(
                                    context,
                                  ),
                                  child: const DecoratedBox(
                                    decoration: BoxDecoration(
                                      color: Colors.white,
                                      shape: BoxShape.circle,
                                    ),
                                    child: Icon(
                                      Icons.location_on,
                                      color: Color(0xFF176B55),
                                      size: 36,
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                      ],
                    ),
                    Positioned(
                      right: 16,
                      top: 16,
                      child: Column(
                        children: [
                          IconButton.filledTonal(
                            tooltip: AppMessage.zoomIn.of(context),
                            onPressed: () => _controller.move(
                              _controller.camera.center,
                              (_controller.camera.zoom + 1)
                                  .clamp(2, 19)
                                  .toDouble(),
                            ),
                            icon: const Icon(Icons.add),
                          ),
                          const SizedBox(height: 8),
                          IconButton.filledTonal(
                            tooltip: AppMessage.zoomOut.of(context),
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
              ColoredBox(
                color: colors.surface,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextButton(
                      onPressed: () => _attribution(mapSettings),
                      child: Text(mapSettings.attribution),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
                      child: FilledButton(
                        onPressed: _point == null || _failed
                            ? null
                            : () => Navigator.pop(context, _point),
                        child: Text(AppMessage.siteMapUseCentre.of(context)),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
