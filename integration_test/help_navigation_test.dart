import 'package:clockrove/help/help_screen.dart';
import 'package:clockrove/l10n/app_localizations.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('opens installed Help and reads the Android app version', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: const [
          AppLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        home: Scaffold(appBar: AppBar(actions: const [HelpButton()])),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byType(HelpButton));
    await tester.pumpAndSettle();

    final helpList = find.byType(Scrollable).first;
    final version = find.textContaining('Clockrove version ');
    await tester.scrollUntilVisible(version, 180, scrollable: helpList);
    expect(version, findsOneWidget);
    final search = find.byType(TextField);
    await tester.scrollUntilVisible(search, -180, scrollable: helpList);
    await tester.enterText(search, 'sample missing topic');
    await tester.pumpAndSettle();
    expect(find.text('No matching help topics.'), findsOneWidget);
    await tester.enterText(search, 'proposed merge');
    await tester.pumpAndSettle();
    await tester.tap(find.text(HelpTopic.backups.title.english));
    await tester.pumpAndSettle();
    expect(find.text(HelpTopic.backups.content), findsOneWidget);
    debugPrint('CLOCKROVE_HELP_NAVIGATION version_and_search=true');
  });
}
