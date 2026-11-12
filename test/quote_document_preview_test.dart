import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/quote_api.dart';
import 'package:clockrove/business/quote_document_screen.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

const _ready = bool.fromEnvironment('CLOCKROVE_DEVICE_PREVIEW');

class _QuotePage extends QuoteApi {
  _QuotePage(this.bytes);
  final Uint8List bytes;
  final calls = <String>[];
  @override
  Future<InvoicePage> preview(String id, int revision, int page) async {
    calls.add('preview/$id/$revision/$page');
    return InvoicePage(
      0,
      1,
      bytes,
      notice: 'Historical quote revision. The current issued revision is 2.',
    );
  }

  @override
  Future<void> email(String id, int revision) async {
    calls.add('email/$id/$revision');
  }

  @override
  Future<bool> savePdf(String id, int revision) async {
    calls.add('save/$id/$revision');
    return false;
  }
}

Future<_QuotePage> _show(
  WidgetTester tester,
  String style, {
  bool dark = false,
  double scale = 1,
  Key? boundary,
}) async {
  final png = (await tester.runAsync(
    () =>
        File('build/native-ui-preview/pdf-api-35/clockrove-quote-$style.png')
            .readAsBytes(),
  ))!;
  final api = _QuotePage(png);
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundary,
      child: ProviderScope(
        overrides: [quoteApiProvider.overrideWithValue(api)],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: QuoteDocumentScreen(
            quoteId: 'quote-real-$style',
            revision: 1,
            number:
                'Q-${1000 + ['minimal', 'classic', 'modern'].indexOf(style)}',
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  await tester.runAsync(
    () => precacheImage(MemoryImage(png), tester.element(find.byType(Image))),
  );
  await tester.pumpAndSettle();
  expect(
    tester.renderObject<RenderImage>(find.byType(RawImage)).image,
    isNotNull,
  );
  return api;
}

void main() {
  for (final style in ['minimal', 'classic', 'modern']) {
    for (final dark in [false, true]) {
      testWidgets(
        'actual Android $style quote viewer ${dark ? 'dark' : 'light'}',
        (tester) async {
          await tester.runAsync(loadMapPreviewFonts);
          tester.view.physicalSize = const Size(420, 920);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final key = GlobalKey();
          final api = await _show(tester, style, dark: dark, boundary: key);
          expect(api.calls.single, 'preview/quote-real-$style/1/0');
          expect(find.text('Page 1 of 1'), findsOneWidget);
          await tester.runAsync(() async {
            final image =
                await (key.currentContext!.findRenderObject()!
                        as RenderRepaintBoundary)
                    .toImage(pixelRatio: 2);
            final bytes = await image.toByteData(
              format: ui.ImageByteFormat.png,
            );
            Directory('build/invoice-viewer-preview')
                .createSync(recursive: true);
            await File(
              'build/invoice-viewer-preview/quote-$style-${dark ? 'dark' : 'light'}.png',
            ).writeAsBytes(bytes!.buffer.asUint8List());
            image.dispose();
          });
          expect(tester.takeException(), isNull);
        },
        skip: !_ready,
      );
    }
  }
  testWidgets(
    'quote landscape large text preserves revision handoff and cancellation wording',
    (tester) async {
      tester.view.physicalSize = const Size(800, 420);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = await _show(tester, 'modern', scale: 2);
      final email = find.widgetWithText(FilledButton, 'Email quote');
      await tester.ensureVisible(email);
      await tester.pumpAndSettle();
      expect(email.hitTestable(), findsOneWidget);
      await tester.tap(email);
      await tester.pumpAndSettle();
      expect(api.calls, contains('email/quote-real-modern/1'));
      expect(
        find.textContaining('press Send in your email app.'),
        findsOneWidget,
      );
      final save = find.widgetWithText(OutlinedButton, 'Save PDF');
      await tester.ensureVisible(save);
      await tester.pumpAndSettle();
      expect(save.hitTestable(), findsOneWidget);
      await tester.tap(save);
      await tester.pumpAndSettle();
      expect(api.calls, contains('save/quote-real-modern/1'));
      expect(
        find.text('Save cancelled. Your issued quote is retained.'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
    skip: !_ready,
  );
}
