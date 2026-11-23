import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app_localizations.dart';

const languageChannel = MethodChannel('clockrove/language/v1');

final languagePreferenceProvider =
    AsyncNotifierProvider<LanguagePreference, String?>(LanguagePreference.new);

class LanguagePreference extends AsyncNotifier<String?> {
  @override
  Future<String?> build() async {
    try {
      final tag = await languageChannel.invokeMethod<String>('get');
      return appLanguages.containsKey(tag) ? tag : null;
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  Future<void> save(String? tag) async {
    await languageChannel.invokeMethod<void>('set', tag);
    state = AsyncData(tag);
  }
}
