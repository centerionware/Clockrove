import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/invoice_screen.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

const _devicePagesReady = bool.fromEnvironment('CLOCKROVE_DEVICE_PREVIEW');

class _ActualPage extends ReportApi {
  _ActualPage(this.bytes);
  final Uint8List bytes;
  int emailCalls = 0, saveCalls = 0;
  @override
  Future<InvoicePage> preview(String id, int page) async =>
      InvoicePage(0, 1, bytes);
  @override
  Future<void> email(String id) async {
    emailCalls++;
  }

  @override
  Future<bool> save(String id) async {
    saveCalls++;
    return false;
  }
}

Future<Uint8List> _page(WidgetTester tester, String style) async =>
    (await tester.runAsync(
      () => File(
        'build/native-ui-preview/pdf-api-35/clockrove-invoice-$style.png',
      ).readAsBytes(),
    ))!;
Future<void> _show(
  WidgetTester tester,
  _ActualPage api, {
  bool dark = false,
  double scale = 1,
  Key? boundary,
}) async {
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundary,
      child: ProviderScope(
        overrides: [reportApiProvider.overrideWithValue(api)],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
          builder: (_, child) => MediaQuery(
            data: MediaQueryData(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: const InvoiceScreen(invoiceId: 'synthetic-actual-document'),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  for (final style in ['minimal', 'classic', 'modern']) {
    for (final dark in [false, true]) {
      testWidgets(
        'render actual Android $style document viewer ${dark ? 'dark' : 'light'}',
        (tester) async {
          await tester.runAsync(loadMapPreviewFonts);
          tester.view.physicalSize = const Size(420, 920);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          final api = _ActualPage(await _page(tester, style));
          final key = GlobalKey();
          await _show(tester, api, dark: dark, boundary: key);
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
              'build/invoice-viewer-preview/clockrove-$style-${dark ? 'dark' : 'light'}.png',
            ).writeAsBytes(bytes!.buffer.asUint8List());
            image.dispose();
          });
          expect(find.byType(Image), findsOneWidget);
          expect(find.text('Page 1 of 1'), findsOneWidget);
          expect(tester.takeException(), isNull);
        },
        skip: !_devicePagesReady,
      );
    }
  }
  testWidgets(
    'landscape large text keeps invoice controls scrollable and handoff wording honest',
    (tester) async {
      tester.view.physicalSize = const Size(800, 420);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = _ActualPage(await _page(tester, 'modern'));
      await _show(tester, api, scale: 2);
      await tester.ensureVisible(find.text('Email invoice'));
      await tester.tap(find.text('Email invoice'));
      await tester.pumpAndSettle();
      expect(api.emailCalls, 1);
      expect(find.textContaining('Email composer opened.'), findsOneWidget);
      expect(
        find.textContaining('press Send in your email app.'),
        findsOneWidget,
      );
      await tester.ensureVisible(find.text('Save PDF'));
      await tester.tap(find.text('Save PDF'));
      await tester.pumpAndSettle();
      expect(api.saveCalls, 1);
      expect(
        find.text('Save cancelled. Your prepared invoice is kept.'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
    skip: !_devicePagesReady,
  );
}
