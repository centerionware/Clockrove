import 'package:clockrove/help/help_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
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
}
