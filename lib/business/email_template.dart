import '../l10n/app_localizations.dart';

const emailTemplatePlaceholders = {
  'company_name',
  'customer_name',
  'job_name',
  'invoice_number',
  'amount',
  'currency',
};

/// Mirrors the native grammar for immediate field feedback. Native validation
/// still runs transactionally; no template text is evaluated or used as regex.
AppMessage? emailTemplateError(String value, {required bool subject}) {
  if (value.length > (subject ? 500 : 20000)) {
    return AppMessage.invoiceEmailTooLong;
  }
  if (value.contains('\u0000') ||
      (subject &&
          value.codeUnits.any(
            (c) => c < 32 || c == 127 || c >= 128 && c <= 159,
          ))) {
    return subject
        ? AppMessage.invoiceEmailSubjectControl
        : AppMessage.invoiceEmailBodyControl;
  }
  var index = 0;
  while (index < value.length) {
    if (value[index] == '}') {
      return AppMessage.invoiceEmailUnmatchedBrace;
    }
    if (value[index] == '{') {
      final end = value.indexOf('}', index + 1);
      if (end < 0) {
        return AppMessage.invoiceEmailUnfinishedPlaceholder;
      }
      if (!emailTemplatePlaceholders.contains(
        value.substring(index + 1, end),
      )) {
        return AppMessage.invoiceEmailUnknownPlaceholder;
      }
      index = end + 1;
    } else {
      index++;
    }
  }
  return null;
}
