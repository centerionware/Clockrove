import 'dart:async';

import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/job_settings_screen.dart';
import 'package:clockrove/business/settings_api.dart';
import 'package:clockrove/business/logo_controls.dart';
import 'package:clockrove/business/settings_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

class _Timer extends TimerApi {
  _Timer([this.events = const Stream.empty()]);
  final Stream<int> events;
  @override
  Stream<int> get committedVersions => events;
}

class _LogoApi extends LogoApi {
  @override
  Future<CompanyLogo> read() async => const CompanyLogo(1, null, null);
}

class _SettingsApi extends BusinessSettingsApi {
  static const company = CompanySettings(
    currency: 'USD',
    digits: 2,
    defaultHourlyMinor: '8500',
    companyName: 'Riverside Studio',
    companyEmail: 'billing@example.test',
    companyPhone: '+1 555 0100',
    companyAddress: '12 Synthetic Street',
    companyWebsite: 'https://example.test',
    invoiceHeader: '',
    invoiceFooter: '',
    invoiceStyle: 'MODERN',
    emailSubject: 'Invoice {invoice_number}',
    emailBody: 'Hello {customer_name},\n\nPlease find your invoice attached.\n{company_name}',
  );
  static const contact = Customer(
    id: 'customer',
    name: 'Synthetic Customer',
    email: 'customer@example.test',
  );
  Future<TimerOutcome> Function()? onSave;
  CompanySettings? savedSettings;
  Customer? savedCustomer;
  JobPreferences? savedJob;
  @override
  Future<BusinessSettings> read() async =>
      BusinessSettings(savedSettings == null ? 1 : 2, savedSettings ?? company, [contact]);
  @override
  Future<JobPreferences> job(String id) async => JobPreferences(
    version: 1,
    id: id,
    name: 'Riverside renovation',
    color: 0xff176b55,
    mode: 'HOURLY',
    currency: 'USD',
    digits: 2,
    archived: false,
    effectiveEmail: contact.email,
    customerId: contact.id,
    effectiveRate: '8500',
  );
  @override
  Future<TimerOutcome> settings(
    String id,
    int version,
    CompanySettings s,
  ) async {
    savedSettings = s;
    return onSave == null ? const TimerOutcome(2, null) : await onSave!();
  }

  @override
  Future<TimerOutcome> customer(String id, int version, Customer c) async {
    savedCustomer = c;
    return const TimerOutcome(2, null);
  }

  @override
  Future<TimerOutcome> saveJob(String id, JobPreferences p) async {
    savedJob = p;
    return const TimerOutcome(2, null);
  }
}

Future<void> _show(
  WidgetTester tester,
  _SettingsApi api,
  Widget screen, {
  bool dark = false,
  double scale = 1,
  Key? boundary,
  TimerApi? timer,
}) async {
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundary,
      child: ProviderScope(
        overrides: [
          businessSettingsApiProvider.overrideWithValue(api),
          logoApiProvider.overrideWithValue(_LogoApi()),
          timerApiProvider.overrideWithValue(timer ?? _Timer()),
        ],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
          builder: (_, child) => MediaQuery(
            data: MediaQueryData(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(builder: (_) => screen),
                ),
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open'));
  await tester.pumpAndSettle();
}

Future<void> _reveal(WidgetTester tester, String label) async {
  await tester.scrollUntilVisible(
    find.text(label),
    250,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('main settings survives a committed native version event before its save reply', (tester) async {
    final events = StreamController<int>();
    addTearDown(events.close);
    final reply = Completer<TimerOutcome>();
    final api = _SettingsApi()..onSave = () {
      events.add(2);
      return reply.future;
    };
    await _show(tester, api, const SettingsScreen(), timer: _Timer(events.stream));
    await tester.enterText(find.widgetWithText(TextField, 'Default hourly rate (USD)'), '123.45');
    await _reveal(tester, 'Save settings');
    await tester.tap(find.text('Save settings'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Settings'), findsOneWidget);
    expect(api.savedSettings!.defaultHourlyMinor, '12345');
    reply.complete(const TimerOutcome(2, null));
    await tester.pumpAndSettle();
    expect(find.text('Open'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'global hourly amount persists as exact minor units with company templates',
    (tester) async {
      final api = _SettingsApi();
      await _show(tester, api, const SettingsScreen());
      await tester.enterText(
        find.widgetWithText(TextField, 'Default hourly rate (USD)'),
        '1234.56',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings!.defaultHourlyMinor, '123456');
      expect(api.savedSettings!.companyName, 'Riverside Studio');
      expect(api.savedSettings!.emailBody, _SettingsApi.company.emailBody);
      expect(api.savedSettings!.invoiceStyle, 'MODERN');
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'customer editing and archiving preserve independent contact identity',
    (tester) async {
      final api = _SettingsApi();
      await _show(
        tester,
        api,
        const CustomerEditor(customer: _SettingsApi.contact),
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Email'),
        'changed@example.test',
      );
      await _reveal(tester, 'Archive customer');
      await tester.tap(find.text('Archive customer'));
      await tester.pumpAndSettle();
      await _reveal(tester, 'Save customer');
      await tester.tap(find.text('Save customer'));
      await tester.pumpAndSettle();
      expect(api.savedCustomer!.id, 'customer');
      expect(api.savedCustomer!.email, 'changed@example.test');
      expect(api.savedCustomer!.archived, isTrue);
      expect(api.savedJob, isNull);
    },
  );
  testWidgets(
    'job uses task billing and a job-only email override without editing customer',
    (tester) async {
      final api = _SettingsApi();
      await _show(tester, api, const JobSettingsScreen(jobId: 'job'));
      await tester.tap(find.text('Per task'));
      await tester.pumpAndSettle();
      await _reveal(tester, 'Use customer email');
      await tester.tap(find.text('Use customer email'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.widgetWithText(TextField, 'Customer email for this job'),
        'job@example.test',
      );
      await _reveal(tester, 'Save job settings');
      await tester.tap(find.text('Save job settings'));
      await tester.pumpAndSettle();
      expect(api.savedJob!.mode, 'TASK');
      expect(api.savedJob!.emailOverride, 'job@example.test');
      expect(api.savedJob!.rate, isNull);
      expect(api.savedCustomer, isNull);
      expect(tester.takeException(), isNull);
    },
  );
  for (final dark in [false, true]) {
    testWidgets(
      'render settings customers and billing ${dark ? 'dark' : 'light'}',
      (tester) async {
        await tester.runAsync(loadMapPreviewFonts);
        tester.view.physicalSize = const Size(420, 920);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final api = _SettingsApi();
        for (final (name, screen) in [
          ('settings', const SettingsScreen()),
          ('customers', const CustomersScreen()),
          ('job-billing', const JobSettingsScreen(jobId: 'job')),
        ]) {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
          final boundary = GlobalKey();
          await _show(tester, api, screen, dark: dark, boundary: boundary);
          await tester.runAsync(() async {
            final image =
                await (boundary.currentContext!.findRenderObject()!
                        as RenderRepaintBoundary)
                    .toImage(pixelRatio: 2);
            final bytes = await image.toByteData(
              format: ui.ImageByteFormat.png,
            );
            Directory('build/ui-preview').createSync(recursive: true);
            await File(
              'build/ui-preview/clockrove-$name-${dark ? 'dark' : 'light'}.png',
            ).writeAsBytes(bytes!.buffer.asUint8List());
            image.dispose();
          });
          expect(tester.takeException(), isNull);
        }
      },
    );
  }
  testWidgets('job billing remains scrollable at large text', (tester) async {
    final api = _SettingsApi();
    await _show(tester, api, const JobSettingsScreen(jobId: 'job'), scale: 2);
    await _reveal(tester, 'Save job settings');
    expect(tester.takeException(), isNull);
  });
}
