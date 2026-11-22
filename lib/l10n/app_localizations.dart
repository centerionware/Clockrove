import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:yaml/yaml.dart';

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

// The fallback also allows individual screens to run under a bare MaterialApp.
enum AppMessage {
  settings('Settings'),
  retrySettings('Retry settings'),
  language('App language'),
  deviceLanguage('Use device language'),
  languageSaveFailed('Could not save the app language. Try again.'),
  backupAndRestore('Backup and restore'),
  workday('Your workday'),
  jobs('Jobs'),
  idle('Not clocked in'),
  running('Clocked in'),
  clockIn('Clock in'),
  clockOut('Clock out'),
  switchJob('Switch job'),
  settingsMenu('App settings and customers'),
  chooseJob('Choose a job below to start your workday.'),
  elapsed('Elapsed this session'),
  exportBackup('Export encrypted backup'),
  restoreBackup('Restore encrypted backup'),
  newJob('New job'),
  jobName('Job name'),
  cancel('Cancel'),
  create('Create'),
  started('Started {time}'),
  refresh('Refresh timer'),
  storageUnavailable('Timer unavailable. Tap to try again.'),
  uncertain('The action may have completed. Retry to confirm its result.'),
  retry('Retry same action'),
  jobCreateFailed(
    'Job was not created. Check its name, refresh the list and try again.',
  ),
  commandConflict(
    'The action could not be applied. Review the current timer and try again.',
  ),
  customers('Customers'),
  reports('Reports'),
  taxFinances('Tax & finances'),
  invoices('Invoices'),
  quotes('Quotes'),
  archivedJobs('Archived jobs'),
  checkDeviceTime('Check device time');

  const AppMessage(this.english);
  final String english;

  static final placeholder = RegExp(r'\{([a-zA-Z][a-zA-Z0-9]*)\}');

  String of(BuildContext context) =>
      Localizations.of<AppLocalizations>(
        context,
        AppLocalizations,
      )?.messages[name] ??
      english;

  String format(BuildContext context, Map<String, String> arguments) =>
      of(context).replaceAllMapped(
        placeholder,
        (match) => arguments[match[1]] ?? (throw ArgumentError(match[0])),
      );
}

class AppLocalizations {
  const AppLocalizations(this.messages);

  final Map<String, String> messages;

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
