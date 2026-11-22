import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:yaml/yaml.dart';

part 'app_languages.g.dart';
part 'app_messages.g.dart';

extension AppMessageTranslation on AppMessage {
  String of(BuildContext context) =>
      Localizations.of<AppLocalizations>(
        context,
        AppLocalizations,
      )?.messages[name] ??
      english;

  String format(BuildContext context, Map<String, String> arguments) =>
      of(context).replaceAllMapped(
        AppLocalizations.placeholder,
        (match) => arguments[match[1]] ?? (throw ArgumentError(match[0])),
      );
}

class AppLocalizations {
  const AppLocalizations(this.messages);

  final Map<String, String> messages;

  static final placeholder = RegExp(r'\{([a-zA-Z][a-zA-Z0-9_]*)\}');
  static const delegate = _AppLocalizationsDelegate();
  static final supportedLocales = [
    for (final (locale, _) in appLanguages.values) locale,
  ];

  static Locale resolveDeviceLocales(
    List<Locale>? preferred,
    Iterable<Locale> supported,
  ) => basicLocaleListResolution([
    for (final locale in preferred ?? const <Locale>[])
      if (locale.languageCode == 'zh' &&
          locale.scriptCode == null &&
          const {'TW', 'HK', 'MO'}.contains(locale.countryCode))
        appLanguages['zh-Hant']!.$1
      else
        locale,
  ], supported);

  static Future<Map<String, String>> loadCatalog(String tag) async {
    final source = await rootBundle.loadString('localizations/$tag.yaml');
    final yaml = loadYaml(source);
    return Map<String, String>.unmodifiable((yaml as YamlMap).cast());
  }
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  bool isSupported(Locale locale) =>
      appLanguages.containsKey(locale.toLanguageTag());

  @override
  Future<AppLocalizations> load(Locale locale) async => AppLocalizations(
    await AppLocalizations.loadCatalog(locale.toLanguageTag()),
  );

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}
