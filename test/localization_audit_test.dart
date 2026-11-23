import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import '../tool/localization_audit.dart';

void main() {
  test('finds literal copy passed indirectly and with interpolation', () {
    final findings = auditDart('lib/screen.dart', r'''
void example(String name) {
  // 'This comment is ignored.'
  final message = 'Record your work ' 'location.';
  final label = 'Hello, $name';
  final channel = 'clockrove/language/v1';
  final code = 'INVALID_LANGUAGE';
}
''');
    expect(findings.map((finding) => finding.text.trim()), [
      'Record your work location.',
      'Hello,',
      'clockrove/language/v1',
    ]);
  });

  test('resource lookups inside Kotlin interpolation are not literal copy', () {
    final findings = auditKotlinPresentation(r'''
flow.write(
    "${strings.getString(R.string.report_pdf_cash_amount, "${money(amount, digits)} ${currency}")} · " +
        strings.getString(R.string.report_pdf_no_cash_activity),
)
''');
    expect(findings, isEmpty);
  });

  test('invoice, payment, and quote invoice screens use catalog copy', () {
    const screens = {
      'invoice_detail_screen',
      'invoice_history_screen',
      'invoice_lifecycle_editor',
      'payment_editor',
      'invoice_review_screen',
      'invoice_screen',
      'invoice_style_preview_screen',
      'quote_invoice_screen',
      'quote_screens',
      'quote_detail_screen',
      'quote_document_screen',
      'quote_editor',
      'quote_scope_screen',
      'quote_related_invoices_screen',
      'legacy_invoice_screen',
      'legacy_covered_time_dialog',
      'business_command',
      'task_conversation_screen',
    };
    const identifiers = {
      'balance-',
      'prepare-invoice',
      'receipt-',
      'void-',
      'invoice-balance-copy-command',
      'invoice-correction-price-',
      'invoice-correction-quantity-',
      'invoice-correction-reason',
      'invoice-correction-tax',
      'invoice-lifecycle-command',
      'invoice-lifecycle-submit',
      'legacy-invoice-review-note',
      'save-legacy-coverage',
      'task-comment-body',
      'task-attachment-',
      'clockrove/documents/v1',
      'clockrove/help/v1',
      'appVersion',
      'paidLabel',
      'dueLabel',
      'captureTaskPhoto',
      'pickTaskPhoto',
      'invoice-payment-amount',
      'invoice-payment-command',
      'invoice-payment-note',
      'invoice-payment-submit',
      'invoice-job/',
      'invoice-task/',
      'invoice-time/',
      'quote-invoice-first',
      'quote-invoice-last',
      'quote-invoice-zone',
      'quote-invoice-note',
      'quote-invoice/',
      'quote-issue-card/',
      'issue/',
      'quote-decision-card/',
      'quote-decision-note',
      'decision/',
      'quote-editor-',
      'quote-scope-note',
      'scope/',
      'customer/',
      'job/',
      'currency/',
      'save-quote/',
    };
    for (final screen in screens) {
      final path = 'lib/business/$screen.dart';
      final source = File(path).readAsStringSync();
      final lines = source.split('\n');
      final candidates = auditDart(path, source).where(
        (finding) =>
            !lines[finding.line - 1].startsWith('import ') &&
            !identifiers.any(
              (identifier) => finding.text.trim().startsWith(identifier),
            ),
      );
      expect(candidates, isEmpty, reason: path);
    }
  });

  test('every Flutter presentation surface uses catalog copy', () {
    const additionalSurfaces = {
      'main.dart',
      'board_view.dart',
      'backup_restore_preview.dart',
      'report_map.dart',
      'logo_controls.dart',
      'history_time_format.dart',
      'site_editor.dart',
      'site_map_picker.dart',
      'work_location_map.dart',
      'elapsed_display.dart',
      'business_command.dart',
      'settings_validation_messages.dart',
      'actual_cost_labels.dart',
      'quote_screens.dart',
      'quote_editor.dart',
      'quote_invoice_format.dart',
      'payment_editor.dart',
      'invoice_lifecycle_editor.dart',
      'email_template.dart',
    };
    const identifiers = {
      'captureTaskPhoto',
      'pickTaskPhoto',
      'invoice-balance-copy-command',
      'invoice-correction-price-',
      'invoice-correction-quantity-',
      'invoice-correction-reason',
      'invoice-correction-tax',
      'invoice-lifecycle-command',
      'invoice-lifecycle-submit',
      'invoice-payment-amount',
      'invoice-payment-command',
      'invoice-payment-note',
      'invoice-payment-submit',
      'invoice-job/',
      'invoice-task/',
      'invoice-time/',
      'quote-invoice-first',
      'quote-invoice-last',
      'quote-invoice-zone',
      'quote-invoice-note',
      'quote-issue-card/',
      'quote-decision-card/',
      'task-comment-body',
      'task-attachment-',
      'paidLabel',
      'dueLabel',
      'quote-editor-',
    };

    bool isSurface(File file) {
      final name = file.uri.pathSegments.last;
      return name == 'main.dart' ||
          additionalSurfaces.contains(name) ||
          name.endsWith('_screen.dart') ||
          name.endsWith('_dialog.dart') ||
          name.endsWith('_controls.dart');
    }

    bool isInternalLiteral(String text, List<String> lines, int line) {
      final value = text.trim();
      final sourceLine = lines[line - 1];
      if (sourceLine.startsWith('import ')) return true;
      if (identifiers.any(value.startsWith)) return true;
      if (sourceLine.contains('ValueKey(') ||
          sourceLine.contains('Key(') ||
          sourceLine.contains('identifier:') ||
          sourceLine.contains('debugPrint(') ||
          sourceLine.contains('uri.path')) {
        return true;
      }
      final contextStart = (line - 4).clamp(0, lines.length).toInt();
      final context = lines.sublist(contextStart, line).join('\n');
      return context.contains('throw ') &&
          (context.contains('FormatException') ||
              context.contains('StateError'));
    }

    final files = Directory('lib')
        .listSync(recursive: true, followLinks: false)
        .whereType<File>()
        .where((file) => file.path.endsWith('.dart') && isSurface(file));
    expect(files, isNotEmpty);
    for (final file in files) {
      final path = file.path;
      final source = file.readAsStringSync();
      final lines = source.split('\n');
      final findings = auditDart(path, source).where(
        (finding) => !isInternalLiteral(finding.text, lines, finding.line),
      );
      expect(findings, isEmpty, reason: path);
    }
  });

  test('Android presentation surfaces use localized resources', () {
    const root =
        'android/app/src/main/kotlin/io/github/centerionware/clockrove';
    const nativeSurfaces = [
      '$root/invoice/InvoicePdf.kt',
      '$root/invoice/InvoiceDocuments.kt',
      '$root/invoice/BusinessReportPdf.kt',
      '$root/invoice/DocumentHostApi.kt',
      '$root/invoice/InvoiceProvider.kt',
      '$root/location/WorkLocationService.kt',
      '$root/location/geofence/GeofenceRuntime.kt',
      '$root/widget/TimerWidget.kt',
      '$root/widget/WidgetTasksService.kt',
      '$root/widget/WidgetActivities.kt',
    ];
    for (final path in nativeSurfaces) {
      final source = File(path).readAsStringSync();
      expect(auditKotlinPresentation(source), isEmpty, reason: path);
    }

    final reportStore = File('$root/tracking/ReportStore.kt')
        .readAsStringSync();
    final reportCsv = reportStore.substring(
      reportStore.indexOf('internal object ReportCsv {'),
    );
    expect(auditKotlinPresentation(reportCsv), isEmpty, reason: 'ReportCsv');

    final xmlFiles = Directory('android/app/src/main')
        .listSync(recursive: true, followLinks: false)
        .whereType<File>()
        .where((file) => file.path.endsWith('.xml'));
    for (final file in xmlFiles) {
      expect(
        auditAndroidPresentationXml(file.readAsStringSync()),
        isEmpty,
        reason: file.path,
      );
    }
  });
}
