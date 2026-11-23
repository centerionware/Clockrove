import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';

String historyOriginLabel(BuildContext context, String origin) =>
    switch (origin) {
      'MANUAL' => AppMessage.historyOriginManual.of(context),
      'WIDGET' => AppMessage.historyOriginWidget.of(context),
      'NOTIFICATION' => AppMessage.historyOriginNotification.of(context),
      'GEOFENCE' => AppMessage.historyOriginGeofence.of(context),
      _ => AppMessage.historySavedChange.of(context),
    };

String historyUtc(BuildContext context, int instant) {
  try {
    final value = DateTime.fromMillisecondsSinceEpoch(instant, isUtc: true);
    final localizations = MaterialLocalizations.of(context);
    return AppMessage.historyUtcTimestamp.format(context, {
      'date': localizations.formatMediumDate(value),
      'time': localizations.formatTimeOfDay(TimeOfDay.fromDateTime(value)),
    });
  } on ArgumentError {
    return AppMessage.historyTimestampUnavailable.of(context);
  }
}
