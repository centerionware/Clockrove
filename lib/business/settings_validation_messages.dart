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

String localizedBusinessFormatError(
  BuildContext context,
  FormatException error,
) {
  if (error.message.startsWith('CURRENCY_DECIMAL_PLACES:')) {
    return AppMessage.paymentAmountFormat.format(context, {
      'digits': error.message.split(':').last,
    });
  }
  return switch (error.message) {
    'TAX_PERCENT_OUT_OF_RANGE' => AppMessage.validationTaxBounds.of(context),
    'INVALID_TAX_PERCENTAGE' || 'INVALID_SAVED_TAX_PERCENTAGE' =>
      AppMessage.validationTaxFormat.of(context),
    'INVALID_MINIMUM_HOURS' => AppMessage.validationMinimumHours.of(context),
    'AMOUNT_TOO_LONG' => AppMessage.quoteInvoiceTooLarge.of(context),
    'INVALID_AMOUNT_FORMAT' => AppMessage.quoteUnitPriceInvalid.of(context),
    'INVALID_TASK_QUANTITY' ||
    'TASK_QUANTITY_OUT_OF_RANGE' => AppMessage.quoteQuantityInvalid.of(context),
    _ => AppMessage.businessInputInvalid.of(context),
  };
}

String? settingsHoursInputError(BuildContext context, String input) =>
    minimumHoursError(input) == null
    ? null
    : AppMessage.validationMinimumHours.of(context);

String? settingsReceivablesInputError(BuildContext context, String input) =>
    isValidAccountingEmail(input)
    ? null
    : AppMessage.validationReceivablesEmail.of(context);
