import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'l10n/app_strings.dart';
import 'app_theme.dart';
import 'tracking/timer_screen.dart';

void main() => runApp(const ProviderScope(child: ClockroveApp()));

class ClockroveApp extends StatelessWidget {
  const ClockroveApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: AppStrings.appName,
    theme: ClockroveTheme.light(),
    darkTheme: ClockroveTheme.dark(),
    home: const TimerScreen(),
  );
}
