import 'package:flutter/material.dart';

import 'automation_api.dart';

class SiteEditResult {
  const SiteEditResult(this.site, {this.remove = false});
  final WorkSite site;
  final bool remove;
}

class SiteEditor extends StatefulWidget {
  const SiteEditor({super.key, this.site});
  final WorkSite? site;
  @override
  State<SiteEditor> createState() => _SiteEditorState();
}

class _SiteEditorState extends State<SiteEditor> {
  final _form = GlobalKey<FormState>();
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
    _name.dispose();
    _latitude.dispose();
    _longitude.dispose();
    _radius.dispose();
    super.dispose();
  }

  String? _number(String? raw, double min, double max) {
    final value = double.tryParse(raw?.trim() ?? '');
    return value == null || !value.isFinite || value < min || value > max
        ? 'Enter a number from ${min.toStringAsFixed(0)} to ${max.toStringAsFixed(0)}.'
        : null;
  }

  void _save() {
    if (!_form.currentState!.validate()) return;
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
      ),
    );
  }

  Future<void> _remove() async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Remove work site?'),
        content: const Text(
          'This removes site monitoring for this place. Recorded work stays saved and any running timer keeps going.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Remove site'),
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
      title: Text(widget.site == null ? 'New work site' : 'Edit work site'),
    ),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: Form(
          key: _form,
          child: ListView(
            padding: const EdgeInsets.all(24),
            children: [
              Text(
                'A place where you work',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              const Text(
                'Use a circular area around the site. A larger radius can help when location accuracy varies.',
              ),
              const SizedBox(height: 24),
              TextFormField(
                controller: _name,
                decoration: const InputDecoration(labelText: 'Site name'),
                textCapitalization: TextCapitalization.words,
                maxLength: 120,
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Enter a site name.'
                    : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _latitude,
                decoration: const InputDecoration(
                  labelText: 'Latitude',
                  helperText: 'Decimal degrees, from −90 to 90',
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
                decoration: const InputDecoration(
                  labelText: 'Longitude',
                  helperText: 'Decimal degrees, from −180 to 180',
                ),
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                  signed: true,
                ),
                validator: (v) => _number(v, -180, 180),
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _radius,
                decoration: const InputDecoration(
                  labelText: 'Radius in metres',
                  helperText: '150–10,000 m',
                ),
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                validator: (v) => _number(v, 150, 10000),
              ),
              const SizedBox(height: 16),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Use this site'),
                value: _enabled,
                onChanged: (value) => setState(() => _enabled = value),
              ),
              const SizedBox(height: 16),
              const Text(
                'Saving resets site detection for this job and clears any pending departure. A running timer keeps going.',
              ),
              const SizedBox(height: 24),
              FilledButton(onPressed: _save, child: const Text('Save site')),
              if (widget.site != null)
                TextButton(
                  onPressed: _remove,
                  child: const Text('Remove site'),
                ),
            ],
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
    title: const Text('Departure grace'),
    content: Form(
      key: _form,
      child: TextFormField(
        controller: _input,
        autofocus: true,
        keyboardType: TextInputType.number,
        decoration: const InputDecoration(
          labelText: 'Minutes',
          helperText: '0–1,440; default 30',
        ),
        validator: (value) {
          final n = int.tryParse(value?.trim() ?? '');
          return n == null || n < 0 || n > 1440
              ? 'Enter a whole number from 0 to 1,440.'
              : null;
        },
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      TextButton(
        onPressed: () {
          if (_form.currentState!.validate()) {
            Navigator.pop(context, int.parse(_input.text.trim()));
          }
        },
        child: const Text('Save'),
      ),
    ],
  );
}
