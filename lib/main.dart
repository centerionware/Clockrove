import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'l10n/app_strings.dart';
import 'app_theme.dart';
import 'tracking/timer_screen.dart';
import 'business/job_screen.dart';
import 'widgets/widget_configuration_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  if (kDebugMode) {
    final present = FlutterError.onError;
    FlutterError.onError = (details) {
      // Debug CI diagnostics contain only type/stack, never field values or a
      // widget dump. Release builds retain the normal Flutter error handler.
      unawaited(const MethodChannel('clockrove/debug/v1').invokeMethod<void>(
        'flutterError',
        {'type': details.exception.runtimeType.toString(), 'stack': details.stack?.toString() ?? ''},
      ).catchError((Object _) {}));
      present?.call(details);
    };
  }
  runApp(const ProviderScope(child: ClockroveApp()));
}

class ClockroveApp extends StatelessWidget {
  const ClockroveApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: AppStrings.appName,
    theme: ClockroveTheme.light(),
    darkTheme: ClockroveTheme.dark(),
    routes: {'/': (_) => const TimerScreen()},
    onGenerateInitialRoutes: (name) {
      final uri = Uri.parse(name);
      final Widget screen;
      if (uri.path == '/widget-configuration') {
        screen = const WidgetConfigurationScreen();
      } else if (uri.path == '/widget-board' &&
          uri.queryParameters['job'] != null) {
        screen = JobScreen(
          jobId: uri.queryParameters['job']!,
          jobName: uri.queryParameters['name'] ?? 'Job board',
        );
      } else if (uri.path == '/widget-unavailable') {
        screen = Scaffold(
          appBar: AppBar(title: const Text('Job unavailable')),
          body: const Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: Text(
                'This job was archived or removed. Refresh the widget or choose an active job in widget settings.',
              ),
            ),
          ),
        );
      } else {
        screen = const TimerScreen();
      }
      return [
        MaterialPageRoute<void>(
          settings: RouteSettings(name: name),
          builder: (_) => screen,
        ),
      ];
    },
  );
}
