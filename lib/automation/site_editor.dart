import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../l10n/app_localizations.dart';
import '../maps/zillow_link.dart';
import 'automation_api.dart';
import 'site_map_picker.dart';

class SiteEditResult {
  const SiteEditResult(this.site, {this.remove = false, this.currentPosition});
  final WorkSite site;
  final bool remove;
  final SitePosition? currentPosition;
  bool get usedCurrentLocation => currentPosition != null;
}

class SiteEditor extends ConsumerStatefulWidget {
  const SiteEditor({super.key, this.site});
  final WorkSite? site;
  @override
  ConsumerState<SiteEditor> createState() => _SiteEditorState();
}

class _SiteEditorState extends ConsumerState<SiteEditor> {
  final _form = GlobalKey<FormState>();
  late final AutomationApi _api;
  bool _locating = false;
  String? _notice;
  SitePosition? _position;
  @override
  void initState() {
    super.initState();
    _api = ref.read(automationApiProvider);
  }

  late final _name = TextEditingController(text: widget.site?.name ?? '');
  late final _latitude = TextEditingController(
    text: widget.site?.latitude.toString() ?? '',
  );
  late final _longitude = TextEditingController(
    text: widget.site?.longitude.toString() ?? '',
  );
  late final _radius = TextEditingController(
    text: widget.site?.radius.toString() ?? '150',
  );
  late bool _enabled = widget.site?.enabled ?? true;
  @override
  void dispose() {
    if (_locating) {
      unawaited(_api.cancelPosition().catchError((Object _) {}));
    }
    _name.dispose();
    _latitude.dispose();
    _longitude.dispose();
    _radius.dispose();
    super.dispose();
  }

  Future<void> _map() async {
    var lat = double.tryParse(_latitude.text);
    var lon = double.tryParse(_longitude.text);
    bool canCenter(double? latitude, double? longitude) =>
        latitude != null &&
        latitude.isFinite &&
        latitude.abs() <= 85 &&
        longitude != null &&
        longitude.isFinite &&
        longitude.abs() <= 180;
    if (!canCenter(lat, lon)) {
      await _current();
      if (!mounted) return;
      lat = double.tryParse(_latitude.text);
      lon = double.tryParse(_longitude.text);
      if (!canCenter(lat, lon)) {
        setState(() {
          _notice = AppMessage.siteMapNeedsLocation.of(context);
        });
        return;
      }
    }
    final radius = double.tryParse(_radius.text);
    final point = await Navigator.of(context).push<LatLng>(
      MaterialPageRoute(
        builder: (_) => SiteMapPicker(
          latitude: lat,
          longitude: lon,
          radius:
              radius != null &&
                  radius.isFinite &&
                  radius >= 1.8 &&
                  radius <= 10000
              ? radius
              : 150,
        ),
      ),
    );
    if (point != null && mounted) {
      setState(() {
        _latitude.text = point.latitude.toString();
        _longitude.text = point.longitude.toString();
        _position = null;
        _notice = null;
      });
    }
  }

  Future<void> _current() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppMessage.siteUseLocationTitle.of(context)),
        content: Text(AppMessage.siteUseLocationHelp.of(context)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.notNow.of(context)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.continueLabel.of(context)),
          ),
        ],
      ),
    );
    if (accepted != true || !mounted) return;
    setState(() {
      _locating = true;
      _notice = null;
    });
    try {
      SitePosition position;
      try {
        position = await _api.currentPosition();
      } on PlatformException catch (error) {
        if (error.code != 'PRECISE_REQUIRED') rethrow;
        await _api.requestPrecise();
        if (!mounted) return;
        position = await _api.currentPosition();
      }
      if (!mounted) return;
      setState(() {
        _position = position;
        _latitude.text = position.latitude.toString();
        _longitude.text = position.longitude.toString();
      });
    } catch (_) {
      if (mounted) {
        setState(() => _notice = AppMessage.siteNoFreshLocation.of(context));
      }
    } finally {
      if (mounted) {
        setState(() => _locating = false);
      }
    }
  }

  (double, double)? get _coordinates {
    final latitude = double.tryParse(_latitude.text.trim());
    final longitude = double.tryParse(_longitude.text.trim());
    if (latitude == null ||
        longitude == null ||
        !latitude.isFinite ||
        !longitude.isFinite ||
        latitude.abs() > 90 ||
        longitude.abs() > 180) {
      return null;
    }
    return (latitude, longitude);
  }

  Future<void> _openZillow() async {
    final coordinates = _coordinates;
    if (coordinates == null) return;
    try {
      final opened = await launchUrl(
        zillowUriForCoordinates(coordinates.$1, coordinates.$2),
        mode: LaunchMode.externalApplication,
      );
      if (!opened && mounted) {
        setState(() => _notice = AppMessage.siteZillowOpenFailed.of(context));
      }
    } catch (_) {
      if (mounted) {
        setState(() => _notice = AppMessage.siteZillowOpenFailed.of(context));
      }
    }
  }

  String? _number(String? raw, double min, double max) {
    final value = double.tryParse(raw?.trim() ?? '');
    final minLabel = min == min.truncateToDouble()
        ? min.toStringAsFixed(0)
        : min.toString();
    return value == null || !value.isFinite || value < min || value > max
        ? AppMessage.siteNumberRange.format(context, {
            'min': minLabel,
            'max': max.toStringAsFixed(0),
          })
        : null;
  }

  void _save() {
    if (_locating || !_form.currentState!.validate()) return;
    Navigator.pop(
      context,
      SiteEditResult(
        WorkSite(
          widget.site?.id ?? newAutomationId(),
          _name.text.trim(),
          double.parse(_latitude.text.trim()),
          double.parse(_longitude.text.trim()),
          double.parse(_radius.text.trim()),
          _enabled,
        ),
        currentPosition: _position,
      ),
    );
  }

  Future<void> _remove() async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppMessage.siteRemoveTitle.of(context)),
        content: Text(AppMessage.siteRemoveHelp.of(context)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.cancel.of(context)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.removeSite.of(context)),
          ),
        ],
      ),
    );
    if (approved == true && mounted) {
      Navigator.pop(context, SiteEditResult(widget.site!, remove: true));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(
        widget.site == null
            ? AppMessage.siteNewTitle.of(context)
            : AppMessage.siteEditTitle.of(context),
      ),
    ),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: Form(
          key: _form,
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  AppMessage.siteDescription.of(context),
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                Text(AppMessage.siteRadiusGuidance.of(context)),
                const SizedBox(height: 24),
                TextFormField(
                  controller: _name,
                  decoration: InputDecoration(
                    labelText: AppMessage.siteName.of(context),
                  ),
                  textCapitalization: TextCapitalization.words,
                  maxLength: 120,
                  validator: (value) => value == null || value.trim().isEmpty
                      ? AppMessage.siteNameRequired.of(context)
                      : null,
                ),
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed: _locating ? null : _map,
                  icon: const Icon(Icons.map_outlined),
                  label: Text(AppMessage.siteChooseOnMap.of(context)),
                ),
                TextButton.icon(
                  onPressed: _locating ? null : _current,
                  icon: const Icon(Icons.my_location_rounded),
                  label: Text(AppMessage.siteUseCurrentLocation.of(context)),
                ),
                if (_locating) const LinearProgressIndicator(),
                if (_notice != null) Text(_notice!),
                if (_position case final position?)
                  Text(
                    AppMessage.siteCurrentFix.format(context, {
                      'accuracy': position.accuracy.toStringAsFixed(0),
                      'time': MaterialLocalizations.of(context).formatTimeOfDay(
                        TimeOfDay.fromDateTime(position.measuredUtc.toLocal()),
                      ),
                    }),
                  ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _latitude,
                  enabled: !_locating,
                  onChanged: (_) => setState(() => _position = null),
                  decoration: InputDecoration(
                    labelText: AppMessage.siteLatitude.of(context),
                    helperText: AppMessage.siteLatitudeHelp.of(context),
                  ),
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                    signed: true,
                  ),
                  validator: (v) => _number(v, -90, 90),
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _longitude,
                  enabled: !_locating,
                  onChanged: (_) => setState(() => _position = null),
                  decoration: InputDecoration(
                    labelText: AppMessage.siteLongitude.of(context),
                    helperText: AppMessage.siteLongitudeHelp.of(context),
                  ),
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                    signed: true,
                  ),
                  validator: (v) => _number(v, -180, 180),
                ),
                if (_coordinates != null) ...[
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: OutlinedButton.icon(
                      key: const ValueKey('site-open-zillow'),
                      onPressed: _openZillow,
                      icon: const Icon(Icons.open_in_new),
                      label: Text(AppMessage.siteOpenZillow.of(context)),
                    ),
                  ),
                ],
                const SizedBox(height: 16),
                TextFormField(
                  controller: _radius,
                  decoration: InputDecoration(
                    labelText: AppMessage.siteRadius.of(context),
                    helperText: AppMessage.siteRadiusHelp.of(context),
                  ),
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  validator: (v) => _number(v, 1.8, 10000),
                ),
                const SizedBox(height: 16),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(AppMessage.siteUseSite.of(context)),
                  value: _enabled,
                  onChanged: (value) => setState(() => _enabled = value),
                ),
                const SizedBox(height: 16),
                Text(AppMessage.automationResetHelp.of(context)),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _locating ? null : _save,
                  child: Text(AppMessage.siteSave.of(context)),
                ),
                if (widget.site != null)
                  TextButton(
                    onPressed: _locating ? null : _remove,
                    child: Text(AppMessage.removeSite.of(context)),
                  ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

class DepartureGraceDialog extends StatefulWidget {
  const DepartureGraceDialog({super.key, required this.minutes});
  final int minutes;
  @override
  State<DepartureGraceDialog> createState() => _DepartureGraceDialogState();
}

class _DepartureGraceDialogState extends State<DepartureGraceDialog> {
  final _form = GlobalKey<FormState>();
  late final _input = TextEditingController(text: widget.minutes.toString());
  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(AppMessage.automationGraceTitle.of(context)),
    content: Form(
      key: _form,
      child: TextFormField(
        controller: _input,
        autofocus: true,
        keyboardType: TextInputType.number,
        decoration: InputDecoration(
          labelText: AppMessage.siteGraceMinutesLabel.of(context),
          helperText: AppMessage.siteGraceMinutesHelp.of(context),
        ),
        validator: (value) {
          final n = int.tryParse(value?.trim() ?? '');
          return n == null || n < 0 || n > 1440
              ? AppMessage.automationInvalidGrace.of(context)
              : null;
        },
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: Text(AppMessage.cancel.of(context)),
      ),
      TextButton(
        onPressed: () {
          if (_form.currentState!.validate()) {
            Navigator.pop(context, int.parse(_input.text.trim()));
          }
        },
        child: Text(AppMessage.save.of(context)),
      ),
    ],
  );
}
