import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/help/help_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final dark in [false, true]) {
    final theme = dark ? 'dark' : 'light';
    testWidgets('captures the $theme offline Help index and backup guide', (
      tester,
    ) async {
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
      await _savePreview(tester, boundary, 'help-index-$theme');

      await tester.tap(find.text(HelpTopic.backups.title));
      await tester.pumpAndSettle();
      expect(find.text(HelpTopic.backups.content), findsOneWidget);
      await _savePreview(tester, boundary, 'help-backups-$theme');
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

Future<void> _savePreview(
  WidgetTester tester,
  GlobalKey boundary,
  String name,
) async {
  await tester.runAsync(() async {
    final render =
        boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final image = await render.toImage(pixelRatio: 2);
    try {
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      final directory = Directory('build/ui-preview')
        ..createSync(recursive: true);
      await File('${directory.path}/$name.png').writeAsBytes(
        bytes!.buffer.asUint8List(),
      );
    } finally {
      image.dispose();
    }
  });
}
