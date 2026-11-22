import 'package:flutter/widgets.dart';

import '../l10n/app_localizations.dart';
import 'invoice_billing_policy.dart';
import 'settings_api.dart';
import 'tax_rate.dart';

String? settingsTaxInputError(BuildContext context, String input) {
  try {
    parseTaxPercent(input);
    return null;
  } on TaxPercentOutOfRange {
    return AppMessage.validationTaxBounds.of(context);
  } on FormatException {
    return AppMessage.validationTaxFormat.of(context);
  }
}

String? settingsHoursInputError(BuildContext context, String input) =>
    minimumHoursError(input) == null
    ? null
    : AppMessage.validationMinimumHours.of(context);

String? settingsReceivablesInputError(BuildContext context, String input) =>
    accountingEmailError(input) == null
    ? null
    : AppMessage.validationReceivablesEmail.of(context);
