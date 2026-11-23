import 'package:clockrove/l10n/app_localizations.dart';

export 'package:clockrove/l10n/app_localizations.dart' show AppMessage;

String englishCopy(
  AppMessage message, [
  Map<String, String> arguments = const {},
]) => message.english.replaceAllMapped(
  AppLocalizations.placeholder,
  (match) => arguments[match[1]] ?? match[0]!,
);
