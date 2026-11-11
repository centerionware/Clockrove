import 'dart:async';

import 'package:flutter/material.dart';

import '../l10n/app_strings.dart';

String elapsedLabel(DateTime start, DateTime now) {
  final seconds = now.difference(start).inSeconds;
  if (now.isBefore(start)) return AppStrings.checkDeviceTime;
  final hours = (seconds ~/ 3600).toString().padLeft(2, '0');
  final minutes = (seconds ~/ 60 % 60).toString().padLeft(2, '0');
  final remainder = (seconds % 60).toString().padLeft(2, '0');
  return '$hours:$minutes:$remainder';
}

/// Foreground display refresh only. Every value is derived from native timestamps;
/// this widget never accumulates time or writes a timer/database state.
class ElapsedDisplay extends StatefulWidget {
  const ElapsedDisplay({super.key, required this.startUtc});
  final DateTime startUtc;
  @override
  State<ElapsedDisplay> createState() => _ElapsedDisplayState();
}

class _ElapsedDisplayState extends State<ElapsedDisplay>
    with WidgetsBindingObserver {
  Timer? _refresh;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startRefresh();
  }

  void _startRefresh() {
    _refresh?.cancel();
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    if (lifecycle != null && lifecycle != AppLifecycleState.resumed) return;
    _refresh = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _startRefresh();
      setState(() {});
    } else {
      _refresh?.cancel();
    }
  }

  @override
  void dispose() {
    _refresh?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FittedBox(
    fit: BoxFit.scaleDown,
    alignment: Alignment.centerLeft,
    child: Text(
      elapsedLabel(widget.startUtc, DateTime.now().toUtc()),
      style: Theme.of(context).textTheme.displayMedium?.copyWith(
        fontWeight: FontWeight.w600,
        letterSpacing: -1.5,
        fontFeatures: const [FontFeature.tabularFigures()],
      ),
    ),
  );
}
