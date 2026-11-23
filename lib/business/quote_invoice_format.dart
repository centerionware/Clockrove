import 'package:flutter/widgets.dart';

import '../l10n/app_localizations.dart';

String quoteInvoiceDurationLabel(BuildContext context, String millis) {
  final value = BigInt.parse(millis);
  final hour = BigInt.from(3600000);
  final minute = BigInt.from(60000);
  final hours = value ~/ hour;
  final minutes = value.remainder(hour) ~/ minute;
  final remaining = value.remainder(minute);
  final wholeSeconds = remaining ~/ BigInt.from(1000);
  final fraction = remaining
      .remainder(BigInt.from(1000))
      .toString()
      .padLeft(3, '0')
      .replaceFirst(RegExp(r'0+$'), '');
  final seconds = '$wholeSeconds${fraction.isEmpty ? '' : '.$fraction'}';
  return AppMessage.quoteInvoiceDurationFormat.format(context, {
    'hours': hours.toString(),
    'minutes': minutes.toString(),
    'seconds': remaining == BigInt.zero
        ? ''
        : AppMessage.quoteInvoiceDurationSeconds.format(context, {
            'seconds': seconds,
          }),
  });
}
