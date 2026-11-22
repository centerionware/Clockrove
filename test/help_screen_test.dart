import 'package:clockrove/app_theme.dart';
import 'package:clockrove/help/help_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

void main() {
  for (final dark in [false, true]) {
    final theme = dark ? 'dark' : 'light';
    testWidgets('captures the $theme offline Help index and backup guide', (
      tester,
    ) async {
      await tester.runAsync(loadMapPreviewFonts);
      tester.view.physicalSize = const Size(420, 920);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final boundary = GlobalKey();
      await tester.pumpWidget(
        RepaintBoundary(
          key: boundary,
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
            home: const HelpScreen(),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.runAsync(
        () => saveMapPreview(boundary, 'help-index-$theme'),
      );

      await tester.tap(find.text(HelpTopic.backups.title));
      await tester.pumpAndSettle();
      expect(find.text(HelpTopic.backups.content), findsOneWidget);
      await tester.runAsync(
        () => saveMapPreview(boundary, 'help-backups-$theme'),
      );
    });
  }

  testWidgets('searches and opens an offline help topic', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: HelpScreen()));

    await tester.enterText(find.byType(TextField), 'missing topic');
    await tester.pump();
    expect(find.text('No matching help topics.'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'proposed merge');
    await tester.pump();
    expect(find.text(HelpTopic.timekeeping.title), findsNothing);
    await tester.tap(find.text(HelpTopic.backups.title));
    await tester.pumpAndSettle();
    expect(find.text(HelpTopic.backups.content), findsOneWidget);
  });

  testWidgets('keeps Help readable and scrollable at large text sizes', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(2)),
          child: child!,
        ),
        home: const HelpScreen(),
      ),
    );

    final topic = find.text(HelpTopic.locationHistory.title);
    await tester.scrollUntilVisible(
      topic,
      180,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(topic);
    await tester.pumpAndSettle();
    expect(find.text(HelpTopic.locationHistory.content), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shows the installed build version from Android', (tester) async {
    const channel = MethodChannel('clockrove/help/v1');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'appVersion');
      return '0.1.0-internal.42';
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    await tester.pumpWidget(const MaterialApp(home: HelpScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Clockrove version 0.1.0-internal.42'), findsOneWidget);
  });

  testWidgets('contextual Help opens the selected guide directly', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: HelpButton(topic: HelpTopic.automation)),
      ),
    );

    await tester.tap(find.byType(HelpButton));
    await tester.pumpAndSettle();
    expect(find.text(HelpTopic.automation.content), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
  });
}
