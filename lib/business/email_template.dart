const emailTemplatePlaceholders = {
  'company_name', 'customer_name', 'job_name', 'invoice_number', 'amount', 'currency',
};

/// Mirrors the native grammar for immediate field feedback. Native validation
/// still runs transactionally; no template text is evaluated or used as regex.
String? emailTemplateError(String value, {required bool subject}) {
  final field = subject ? 'Email subject template' : 'Email body template';
  if (value.length > (subject ? 500 : 20000)) {
    return '$field is too long. Shorten it and try again.';
  }
  if (value.contains('\u0000') ||
      (subject && value.codeUnits.any((c) => c < 32 || c == 127 || c >= 128 && c <= 159))) {
    return subject
        ? 'Email subject template must be one line without control characters.'
        : 'Email body template contains an unsupported control character.';
  }
  var index = 0;
  while (index < value.length) {
    if (value[index] == '}') {
      return '$field has an unmatched }. Use a complete placeholder such as {company_name}.';
    }
    if (value[index] == '{') {
      final end = value.indexOf('}', index + 1);
      if (end < 0) {
        return '$field has an unfinished placeholder. Close it with }.';
      }
      if (!emailTemplatePlaceholders.contains(value.substring(index + 1, end))) {
        return '$field has an unknown placeholder. Choose one of the supported placeholders above.';
      }
      index = end + 1;
    } else {
      index++;
    }
  }
  return null;
}
