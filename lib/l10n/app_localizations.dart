import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:yaml/yaml.dart';

part 'app_messages.g.dart';

const appLanguages = {
  'en': (Locale('en'), 'English'),
  'ar': (Locale('ar'), 'العربية'),
  'bn': (Locale('bn'), 'বাংলা'),
  'de': (Locale('de'), 'Deutsch'),
  'es': (Locale('es'), 'Español'),
  'fr': (Locale('fr'), 'Français'),
  'hi': (Locale('hi'), 'हिन्दी'),
  'id': (Locale('id'), 'Bahasa Indonesia'),
  'it': (Locale('it'), 'Italiano'),
  'ja': (Locale('ja'), '日本語'),
  'ko': (Locale('ko'), '한국어'),
  'pt': (Locale('pt'), 'Português'),
  'ru': (Locale('ru'), 'Русский'),
  'tr': (Locale('tr'), 'Türkçe'),
  'vi': (Locale('vi'), 'Tiếng Việt'),
  'zh-Hans': (
    Locale.fromSubtags(languageCode: 'zh', scriptCode: 'Hans'),
    '简体中文',
  ),
  'zh-Hant': (
    Locale.fromSubtags(languageCode: 'zh', scriptCode: 'Hant'),
    '繁體中文',
  ),
};

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

  static final placeholder = RegExp(r'\{([a-zA-Z][a-zA-Z0-9]*)\}');
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
