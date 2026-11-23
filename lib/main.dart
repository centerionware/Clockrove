import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'l10n/app_localizations.dart';
import 'l10n/language_preference.dart';
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
      unawaited(
        const MethodChannel('clockrove/debug/v1')
            .invokeMethod<void>('flutterError', {
              'type': details.exception.runtimeType.toString(),
              'stack': details.stack?.toString() ?? '',
            })
            .catchError((Object _) {}),
      );
      present?.call(details);
    };
  }
  runApp(const ProviderScope(child: ClockroveApp()));
}

class ClockroveApp extends ConsumerWidget {
  const ClockroveApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp(
    locale: appLanguages[ref.watch(languagePreferenceProvider).value]?.$1,
    supportedLocales: AppLocalizations.supportedLocales,
    localeListResolutionCallback: AppLocalizations.resolveDeviceLocales,
    localizationsDelegates: const [
      AppLocalizations.delegate,
      ...GlobalMaterialLocalizations.delegates,
    ],
    debugShowCheckedModeBanner: false,
    onGenerateTitle: AppMessage.appName.of,
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
        screen = Builder(
          builder: (context) => JobScreen(
            jobId: uri.queryParameters['job']!,
            jobName:
                uri.queryParameters['name'] ?? AppMessage.boardTitle.of(context),
          ),
        );
      } else if (uri.path == '/widget-unavailable') {
        screen = const _WidgetUnavailableScreen();
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

class _WidgetUnavailableScreen extends StatelessWidget {
  const _WidgetUnavailableScreen();

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(AppMessage.commandJobUnavailable.of(context))),
    body: Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Text(AppMessage.widgetSelectedJobUnavailable.of(context)),
      ),
    ),
  );
}
