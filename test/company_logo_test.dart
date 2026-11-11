import 'dart:async';
import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/logo_controls.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Versions extends TimerApi {
  final controller = StreamController<int>.broadcast();
  @override Stream<int> get committedVersions => controller.stream;
}
class _Logo extends LogoApi {
  _Logo(this.versions, {this.existing = false});
  final _Versions versions;
  bool existing, cancel = false;
  int picks = 0, version = 1;
  final attempts = <(String,int,String)>[];
  @override Future<CompanyLogo> read() async => CompanyLogo(version, existing ? 'synthetic-logo' : null, existing ? TileProvider.transparentImage : null);
  @override Future<LogoDraft?> pick() async { picks++; return cancel ? null : LogoDraft('synthetic-logo', TileProvider.transparentImage); }
  @override Future<TimerOutcome> apply(String id, int expected, String token) async {
    attempts.add((id, expected, token));
    if (attempts.length == 1) { existing = true; version = 2; versions.controller.add(2); throw StateError('Synthetic lost response'); }
    return const TimerOutcome(2, null);
  }
  @override Future<TimerOutcome> remove(String id, int expected) async {
    attempts.add((id, expected, 'remove'));
    if (attempts.length == 1) { existing = false; version = 2; versions.controller.add(2); throw StateError('Synthetic lost response'); }
    return const TimerOutcome(2, null);
  }
}
Future<void> _show(WidgetTester tester, _Logo api, _Versions versions) async {
  await tester.pumpWidget(ProviderScope(overrides: [logoApiProvider.overrideWithValue(api), timerApiProvider.overrideWithValue(versions)], child: MaterialApp(theme: ClockroveTheme.light(), home: Scaffold(body: SingleChildScrollView(child: LogoControls())))));
  await tester.pumpAndSettle();
}
void main() {
  testWidgets('picker cancellation never applies a logo', (tester) async {
    final versions = _Versions(); addTearDown(versions.controller.close); final api = _Logo(versions)..cancel = true;
    await _show(tester, api, versions); await tester.tap(find.text('Choose logo')); await tester.pumpAndSettle();
    expect(api.picks, 1); expect(api.attempts, isEmpty); expect(find.text('Use this logo'), findsNothing);
  });
  testWidgets('logo apply survives refresh and retries the original immutable intent', (tester) async {
    final versions = _Versions(); addTearDown(versions.controller.close); final api = _Logo(versions);
    await _show(tester, api, versions); await tester.tap(find.text('Choose logo')); await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Use this logo')); await tester.tap(find.text('Use this logo')); await tester.pumpAndSettle();
    expect(find.text('Retry same operation'), findsOneWidget); expect(api.attempts.length, 1);
    await tester.tap(find.text('Retry same operation')); await tester.pumpAndSettle();
    expect(api.attempts[0], api.attempts[1]); expect(find.text('Use this logo'), findsNothing); expect(find.text('Change logo'), findsOneWidget); expect(tester.takeException(), isNull);
  });
  testWidgets('removal retry remains available after the saved logo disappears', (tester) async {
    final versions = _Versions(); addTearDown(versions.controller.close); final api = _Logo(versions, existing: true);
    await _show(tester, api, versions); await tester.ensureVisible(find.text('Remove logo')); await tester.tap(find.text('Remove logo')); await tester.pumpAndSettle();
    expect(find.text('Retry same operation'), findsOneWidget); await tester.tap(find.text('Retry same operation')); await tester.pumpAndSettle();
    expect(api.attempts[0], api.attempts[1]); expect(find.text('Choose logo'), findsOneWidget); expect(tester.takeException(), isNull);
  });
}
