import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';

class BackupRestorePasswordField extends StatelessWidget {
  const BackupRestorePasswordField({
    super.key,
    required this.controller,
    required this.enabled,
    required this.visible,
    required this.onToggle,
    required this.errorText,
  });

  final TextEditingController controller;
  final bool enabled;
  final bool visible;
  final VoidCallback onToggle;
  final AppMessage? errorText;

  @override
  Widget build(BuildContext context) => TextField(
    key: const ValueKey('restore-password'),
    controller: controller,
    enabled: enabled,
    obscureText: !visible,
    autocorrect: false,
    enableSuggestions: false,
    decoration: InputDecoration(
      labelText: AppMessage.backupPassword.of(context),
      errorText: errorText?.of(context),
      suffixIcon: IconButton(
        tooltip: visible
            ? AppMessage.hidePassword.of(context)
            : AppMessage.showPassword.of(context),
        onPressed: onToggle,
        icon: Icon(visible ? Icons.visibility_off : Icons.visibility),
      ),
    ),
  );
}
