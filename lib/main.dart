import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'l10n/app_strings.dart';
import 'tracking/timer_screen.dart';

void main() => runApp(const ProviderScope(child: ClockroveApp()));

class ClockroveApp extends StatelessWidget {
  const ClockroveApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: AppStrings.appName,
    theme: ThemeData(colorSchemeSeed: Colors.teal),
    darkTheme: ThemeData(
      colorSchemeSeed: Colors.teal,
      brightness: Brightness.dark,
    ),
    home: const TimerScreen(),
  );
}
