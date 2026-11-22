import 'package:clockrove/l10n/app_localizations.dart';
import 'package:clockrove/l10n/language_preference.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('each shipped catalog covers every migrated message', () async {
    final keys = AppMessage.values.map((message) => message.name).toSet();
    Iterable<String?> placeholders(String text) =>
        AppMessage.placeholder.allMatches(text).map((match) => match[1]);
    for (final tag in appLanguages.keys) {
      final catalog = await AppLocalizations.loadCatalog(tag);
      expect(catalog.keys.toSet(), keys, reason: tag);
      expect(catalog.values, everyElement(isNotEmpty), reason: tag);
      for (final message in AppMessage.values) {
        final translated = catalog[message.name]!;
        expect(
          placeholders(translated),
          unorderedEquals(placeholders(message.english)),
          reason: '$tag:${message.name}',
        );
        if (tag == 'en') expect(translated, message.english);
      }
    }
  });

  test('resolves supported device preferences and Chinese script variants', () {
    Locale resolve(List<Locale> locales) =>
        AppLocalizations.resolveDeviceLocales(
          locales,
          AppLocalizations.supportedLocales,
        );

    expect(resolve([const Locale('es', 'MX')]), const Locale('es'));
    expect(resolve([const Locale('zh', 'TW')]), appLanguages['zh-Hant']!.$1);
    expect(resolve([const Locale('zh', 'CN')]), appLanguages['zh-Hans']!.$1);
    expect(
      resolve([const Locale('nl'), const Locale('fr')]),
      const Locale('fr'),
    );
    expect(resolve([const Locale('nl')]), const Locale('en'));
  });

  test('an override reloads from storage and can be cleared', () async {
    String? stored;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(languageChannel, (call) async {
      switch (call.method) {
        case 'get':
          return stored;
        case 'set':
          stored = call.arguments as String?;
          return null;
        default:
          throw MissingPluginException();
      }
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(languageChannel, null),
    );

    final first = ProviderContainer();
    addTearDown(first.dispose);
    expect(await first.read(languagePreferenceProvider.future), isNull);
    await first.read(languagePreferenceProvider.notifier).save('es');
    expect(first.read(languagePreferenceProvider).value, 'es');

    final reopened = ProviderContainer();
    addTearDown(reopened.dispose);
    expect(await reopened.read(languagePreferenceProvider.future), 'es');
    await reopened.read(languagePreferenceProvider.notifier).save(null);
    expect(reopened.read(languagePreferenceProvider).value, isNull);
    expect(stored, isNull);
  });
}
