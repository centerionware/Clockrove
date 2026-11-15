import 'dart:async';
import 'dart:convert';

import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/job_settings_screen.dart';
import 'package:clockrove/business/settings_api.dart';
import 'package:clockrove/business/logo_controls.dart';
import 'package:clockrove/business/settings_screen.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/business/invoice_style_preview_screen.dart';
import 'package:clockrove/business/invoice_billing_policy.dart';
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

class _PreviewReportApi extends ReportApi {
  static final pixel = base64Decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC',
  );
  final styles = <String>[];

  @override
  Future<InvoicePage> previewStyle({
    required String style,
    required String currency,
    required int digits,
    required Uint8List? logo,
    required int page,
  }) async {
    styles.add(style);
    return InvoicePage(page, 1, Uint8List.fromList(pixel));
  }
}

class _SettingsApi extends BusinessSettingsApi {
  static const company = CompanySettings(
    currency: 'USD',
    digits: 2,
    defaultHourlyMinor: '8500',
    defaultTaxUnits: 75000,
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
  Future<BusinessSettings> read() async => BusinessSettings(
    savedSettings == null ? 1 : 2,
    savedSettings ?? company,
    [contact],
  );
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
    effectiveTaxUnits: (savedSettings ?? company).defaultTaxUnits,
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
  ReportApi? reportApi,
}) async {
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundary,
      child: ProviderScope(
        overrides: [
          businessSettingsApiProvider.overrideWithValue(api),
          logoApiProvider.overrideWithValue(_LogoApi()),
          timerApiProvider.overrideWithValue(timer ?? _Timer()),
          if (reportApi != null) reportApiProvider.overrideWithValue(reportApi),
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
  final scrollable = find.byType(Scrollable).first;
  tester.state<ScrollableState>(scrollable).position.jumpTo(0);
  await tester.pumpAndSettle();
  await tester.scrollUntilVisible(
    find.text(label),
    250,
    scrollable: scrollable,
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('invoice style preview uses the selected style and sample data', (
    tester,
  ) async {
    final reports = _PreviewReportApi();
    await _show(
      tester,
      _SettingsApi(),
      const SettingsScreen(),
      reportApi: reports,
    );
    await _reveal(tester, 'Invoice style');
    await tester.tap(find.byType(DropdownButtonFormField<String>).last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Classic').last);
    await tester.pumpAndSettle();
    await _reveal(tester, 'Preview invoice');
    await tester.tap(find.text('Preview invoice'));
    await tester.pumpAndSettle();
    expect(find.byType(InvoiceStylePreviewScreen), findsOneWidget);
    expect(find.text('Classic · sample invoice'), findsOneWidget);
    expect(
      find.textContaining('does not create or send an invoice'),
      findsOneWidget,
    );
    expect(find.text('Page 1 of 1'), findsOneWidget);
    expect(reports.styles, ['CLASSIC']);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'invalid receivables address keeps the draft and corrected address saves',
    (tester) async {
      final api = _SettingsApi();
      await _show(tester, api, const SettingsScreen());
      const label = 'Accounts receivable email (optional)';
      await _reveal(tester, label);
      await tester.enterText(
        find.widgetWithText(TextField, label),
        'accounts@example.test,other@example.test',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings, isNull);
      await _reveal(tester, label);
      final field = tester.widget<TextField>(
        find.widgetWithText(TextField, label),
      );
      expect(
        field.controller!.text,
        'accounts@example.test,other@example.test',
      );
      expect(field.decoration!.errorText, contains('one email address'));
      await tester.enterText(
        find.widgetWithText(TextField, label),
        'accounts@example.test',
      );
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings!.receivablesEmail, 'accounts@example.test');
      expect(
        api.savedSettings!.companyEmail,
        _SettingsApi.company.companyEmail,
      );
      expect(tester.takeException(), isNull);
    },
  );
  test('receivables codec and validation retain optional fallback and one recipient', () {
    final encoded = _SettingsApi.company.encode();
    encoded['fractionDigits'] = 2;
    expect(CompanySettings.decode(encoded).receivablesEmail, isEmpty);
    expect(
      CompanySettings.decode(encoded).hourlyBillingMode,
      minimumThenExactBilling,
    );
    expect(CompanySettings.decode(encoded).minimumBillableHours, '0');
    expect(CompanySettings.decode(encoded).minimumTaskFeeMinor, '0');
    expect(
      CompanySettings.decode({
        ...encoded,
        'receivablesEmail': 'accounts@example.test',
        'hourlyBillingMode': roundTotalUpBilling,
        'minimumBillableHours': '1.25',
        'minimumTaskFeeMinor': '2500',
      }).encode()['receivablesEmail'],
      'accounts@example.test',
    );
    expect(accountingEmailError(''), isNull);
    expect(accountingEmailError(' accounts@example.test '), isNull);
    for (final value in [
      'bad',
      'a@example.test;b@example.test',
      'a@example.test\nBcc:b@example.test',
      'a@example.test\u0000',
    ]) {
      expect(accountingEmailError(value), isNotNull);
    }
  });
  testWidgets(
    'invalid global tax keeps the draft and corrected percentage saves exactly',
    (tester) async {
      final api = _SettingsApi();
      await _show(tester, api, const SettingsScreen());
      await _reveal(tester, 'Default tax (%)');
      await tester.enterText(
        find.widgetWithText(TextField, 'Default tax (%)'),
        '100.0001',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings, isNull);
      await _reveal(tester, 'Default tax (%)');
      final field = tester.widget<TextField>(
        find.widgetWithText(TextField, 'Default tax (%)'),
      );
      expect(field.controller!.text, '100.0001');
      expect(field.decoration!.errorText, contains('between 0 and 100'));
      await tester.enterText(
        find.widgetWithText(TextField, 'Default tax (%)'),
        '7.5',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings!.defaultTaxUnits, 75000);
      expect(api.savedSettings!.companyName, _SettingsApi.company.companyName);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('job tax can explicitly override a nonzero default with zero', (
    tester,
  ) async {
    final api = _SettingsApi();
    await _show(tester, api, const JobSettingsScreen(jobId: 'job'));
    await _reveal(tester, 'Use default tax rate');
    expect(find.text('7.5% of the invoice subtotal'), findsOneWidget);
    await tester.tap(find.text('Use default tax rate'));
    await tester.pumpAndSettle();
    await _reveal(tester, 'Job tax (%)');
    await tester.enterText(find.widgetWithText(TextField, 'Job tax (%)'), '-1');
    await _reveal(tester, 'Save job settings');
    await tester.tap(find.text('Save job settings'));
    await tester.pumpAndSettle();
    expect(api.savedJob, isNull);
    await _reveal(tester, 'Job tax (%)');
    expect(
      tester
          .widget<TextField>(find.widgetWithText(TextField, 'Job tax (%)'))
          .decoration!
          .errorText,
      isNotNull,
    );
    await tester.enterText(find.widgetWithText(TextField, 'Job tax (%)'), '0');
    await _reveal(tester, 'Save job settings');
    await tester.tap(find.text('Save job settings'));
    await tester.pumpAndSettle();
    expect(api.savedJob!.taxUnits, 0);
    expect(api.savedJob!.encode()['taxUnits'], 0);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'main settings survives a committed native version event before its save reply',
    (tester) async {
      final events = StreamController<int>();
      addTearDown(events.close);
      final reply = Completer<TimerOutcome>();
      final api = _SettingsApi()
        ..onSave = () {
          events.add(2);
          return reply.future;
        };
      await _show(
        tester,
        api,
        const SettingsScreen(),
        timer: _Timer(events.stream),
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Default hourly rate (USD)'),
        '123.45',
      );
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
    },
  );
  testWidgets(
    'both email template errors are inline and block save until corrected',
    (tester) async {
      final api = _SettingsApi();
      await _show(tester, api, const SettingsScreen());
      await _reveal(tester, 'Email subject template');
      await tester.enterText(
        find.widgetWithText(TextField, 'Email subject template'),
        'Invoice {unknown}',
      );
      await _reveal(tester, 'Email body template');
      await tester.enterText(
        find.widgetWithText(TextField, 'Email body template'),
        'Hello {customer_name',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings, isNull);
      expect(
        find.textContaining('Fix the highlighted email subject'),
        findsOneWidget,
      );
      await _reveal(tester, 'Email subject template');
      expect(
        tester
            .widget<TextField>(
              find.widgetWithText(TextField, 'Email subject template'),
            )
            .decoration!
            .errorText,
        contains('unknown placeholder'),
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Email subject template'),
        'Invoice {invoice_number}',
      );
      await _reveal(tester, 'Email body template');
      expect(
        tester
            .widget<TextField>(
              find.widgetWithText(TextField, 'Email body template'),
            )
            .decoration!
            .errorText,
        contains('unfinished placeholder'),
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Email body template'),
        'Hello {customer_name},\n{company_name}',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings!.emailSubject, 'Invoice {invoice_number}');
      expect(
        api.savedSettings!.emailBody,
        'Hello {customer_name},\n{company_name}',
      );
      expect(tester.takeException(), isNull);
    },
  );
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
    'global invoice billing policy validates and saves exact settings',
    (tester) async {
      final api = _SettingsApi();
      await _show(tester, api, const SettingsScreen());
      await _reveal(tester, 'Minimum billable hours');
      await tester.enterText(
        find.widgetWithText(TextField, 'Minimum billable hours'),
        '1e2',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings, isNull);
      await _reveal(tester, 'Minimum billable hours');
      expect(
        tester
            .widget<TextField>(
              find.widgetWithText(TextField, 'Minimum billable hours'),
            )
            .decoration!
            .errorText,
        isNotNull,
      );

      await tester.enterText(
        find.widgetWithText(TextField, 'Minimum billable hours'),
        '1.250000',
      );
      await _reveal(tester, 'Hourly billing rule');
      await tester.tap(
        find
            .ancestor(
              of: find.text('Hourly billing rule'),
              matching: find.byType(DropdownButtonFormField<String>),
            )
            .first,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Round total time up to the next hour').last);
      await tester.pumpAndSettle();
      await _reveal(tester, 'Minimum per-task invoice fee (USD)');
      await tester.enterText(
        find.widgetWithText(TextField, 'Minimum per-task invoice fee (USD)'),
        '25.00',
      );
      await _reveal(tester, 'Save settings');
      await tester.tap(find.text('Save settings'));
      await tester.pumpAndSettle();
      expect(api.savedSettings!.hourlyBillingMode, roundTotalUpBilling);
      expect(api.savedSettings!.minimumBillableHours, '1.25');
      expect(api.savedSettings!.minimumTaskFeeMinor, '2500');
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
      expect(api.savedJob!.taxUnits, isNull);
      expect(api.savedCustomer, isNull);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('job can override each invoice billing default independently', (
    tester,
  ) async {
    final api = _SettingsApi();
    await _show(tester, api, const JobSettingsScreen(jobId: 'job'));
    await _reveal(tester, 'Use default hourly billing rule');
    await tester.tap(find.text('Use default hourly billing rule'));
    await tester.pumpAndSettle();
    await _reveal(tester, 'Hourly billing rule');
    await tester.tap(
      find
          .ancestor(
            of: find.text('Hourly billing rule'),
            matching: find.byType(DropdownButtonFormField<String>),
          )
          .first,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Round total time up to the next hour').last);
    await tester.pumpAndSettle();

    await _reveal(tester, 'Use default minimum billable hours');
    await tester.tap(find.text('Use default minimum billable hours'));
    await tester.pumpAndSettle();
    await _reveal(tester, 'Minimum billable hours for this job');
    await tester.enterText(
      find.widgetWithText(TextField, 'Minimum billable hours for this job'),
      '2',
    );

    await _reveal(tester, 'Use default minimum per-task fee');
    await tester.tap(find.text('Use default minimum per-task fee'));
    await tester.pumpAndSettle();
    await _reveal(tester, 'Minimum task-rate invoice fee (USD)');
    await tester.enterText(
      find.widgetWithText(TextField, 'Minimum task-rate invoice fee (USD)'),
      '50.00',
    );
    await _reveal(tester, 'Save job settings');
    await tester.tap(find.text('Save job settings'));
    await tester.pumpAndSettle();
    expect(api.savedJob!.hourlyBillingMode, roundTotalUpBilling);
    expect(api.savedJob!.minimumBillableHours, '2');
    expect(api.savedJob!.minimumTaskFeeMinor, '5000');
    expect(tester.takeException(), isNull);
  });
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
