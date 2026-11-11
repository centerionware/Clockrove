import 'package:flutter/material.dart';

import 'l10n/app_strings.dart';

void main() => runApp(const ClockroveApp());

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
    home: Scaffold(
      appBar: AppBar(title: const Text(AppStrings.appName)),
      body: const SafeArea(
        child: Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(AppStrings.setupTitle),
                SizedBox(height: 16),
                Text(AppStrings.setupMessage),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
