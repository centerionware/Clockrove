import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app_localizations.dart';
import 'language_preference.dart';

class LanguageSetting extends ConsumerStatefulWidget {
  const LanguageSetting({super.key});

  @override
  ConsumerState<LanguageSetting> createState() => _LanguageSettingState();
}

class _LanguageSettingState extends ConsumerState<LanguageSetting> {
  bool _saving = false;

  Future<void> _save(String tag) async {
    setState(() => _saving = true);
    try {
      await ref
          .read(languagePreferenceProvider.notifier)
          .save(tag.isEmpty ? null : tag);
    } on PlatformException {
      _showError();
    } on MissingPluginException {
      _showError();
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _showError() {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(AppMessage.languageSaveFailed.of(context))),
    );
  }

  @override
  Widget build(BuildContext context) {
    final preference = ref.watch(languagePreferenceProvider);
    final selected = preference.value ?? '';
    return InputDecorator(
      decoration: InputDecoration(labelText: AppMessage.language.of(context)),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<String>(
          value: selected,
          isExpanded: true,
          items: [
            DropdownMenuItem(
              value: '',
              child: Text(
                AppMessage.deviceLanguage.of(context),
                overflow: TextOverflow.ellipsis,
              ),
            ),
            for (final entry in appLanguages.entries)
              DropdownMenuItem(value: entry.key, child: Text(entry.value.$2)),
          ],
          onChanged: _saving || preference.isLoading
              ? null
              : (tag) {
                  if (tag != null && tag != selected) _save(tag);
                },
        ),
      ),
    );
  }
}
