import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/app_localizations.dart';

enum HelpTopic {
  timekeeping(
    AppMessage.helpTimekeeping,
    translatedBody: AppMessage.helpTimekeepingBody,
  ),
  automation(
    AppMessage.helpAutomation,
    fallbackBody:
        'Open a job’s settings to add a work site and enable automation. '
        'Allow precise location and, where Android requires it, background '
        'location. If clock-in on arrival is enabled and you save a site with '
        'Use current location, Clockrove offers to start work now. Saving a '
        'changed site resets arrival detection and waits for a confirmed exit '
        'and fresh entry. Android recommends a 100–150 m radius; smaller '
        'radii can miss arrivals. If an arrival is missed, check that the site '
        'is enabled and use Refresh site monitoring or Retry site monitoring '
        'if shown. '
        'You can clock in or out manually while troubleshooting.',
  ),
  backups(
    AppMessage.helpBackups,
    translatedBody: AppMessage.helpBackupsBody,
  ),
  boards(
    AppMessage.helpBoards,
    translatedBody: AppMessage.helpBoardsBody,
  ),
  reports(
    AppMessage.helpReports,
    translatedBody: AppMessage.helpReportsBody,
  ),
  billing(
    AppMessage.helpBilling,
    translatedBody: AppMessage.helpBillingBody,
  ),
  widgets(
    AppMessage.helpWidgets,
    fallbackBody:
        'Open the Android launcher’s widget picker and add a Clockrove widget. '
        'Choose a default job or choose a job at clock-in. While work is '
        'running, the timer widget shows the actual active job and can stop it. '
        'Task and combined widgets show upcoming work for their configuration.',
  ),
  locationHistory(
    AppMessage.helpLocationHistory,
    translatedBody: AppMessage.helpLocationHistoryBody,
  );

  const HelpTopic(this.title, {String? fallbackBody, AppMessage? translatedBody})
    : _fallbackBody = fallbackBody,
      _translatedBody = translatedBody;

  final AppMessage title;
  final String? _fallbackBody;
  final AppMessage? _translatedBody;

  String get content => _translatedBody?.english ?? _fallbackBody!;
  String body(BuildContext context) =>
      _translatedBody?.of(context) ?? _fallbackBody!;
}

class HelpScreen extends StatefulWidget {
  const HelpScreen({super.key});

  @override
  State<HelpScreen> createState() => _HelpScreenState();
}

class _HelpScreenState extends State<HelpScreen> {
  String _query = '';

  late final Future<String?> _appVersion = _readAppVersion();

  Future<String?> _readAppVersion() async {
    const channel = MethodChannel('clockrove/help/v1');
    try {
      return await channel.invokeMethod<String>('appVersion');
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final topics = HelpTopic.values.where(
      (topic) =>
          topic.title.of(context).toLowerCase().contains(_query) ||
          topic.body(context).toLowerCase().contains(_query),
    );
    return Scaffold(
      appBar: AppBar(title: Text(AppMessage.help.of(context))),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            decoration: InputDecoration(
              labelText: AppMessage.helpSearch.of(context),
              prefixIcon: const Icon(Icons.search),
            ),
            onChanged: (value) =>
                setState(() => _query = value.trim().toLowerCase()),
          ),
          const SizedBox(height: 12),
          if (topics.isEmpty) Text(AppMessage.helpNoResults.of(context)),
          for (final topic in topics)
            ListTile(
              title: Text(topic.title.of(context)),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => HelpTopicScreen(topic: topic),
                ),
              ),
            ),
          FutureBuilder<String?>(
            future: _appVersion,
            builder: (context, snapshot) {
              final version = snapshot.data;
              return version == null || version.isEmpty
                  ? const SizedBox.shrink()
                  : ListTile(
                      title: Text(
                        AppMessage.helpVersion.format(context, {
                          'version': version,
                        }),
                      ),
                    );
            },
          ),
        ],
      ),
    );
  }
}

class HelpTopicScreen extends StatelessWidget {
  const HelpTopicScreen({super.key, required this.topic});

  final HelpTopic topic;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(AppMessage.help.of(context))),
    body: ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text(
          topic.title.of(context),
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 12),
        Text(topic.body(context), style: Theme.of(context).textTheme.bodyLarge),
      ],
    ),
  );
}

class HelpButton extends StatelessWidget {
  const HelpButton({super.key, this.topic, this.enabled = true});

  final HelpTopic? topic;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final selected = topic;
    return IconButton(
      tooltip: selected == null
          ? AppMessage.help.of(context)
          : AppMessage.helpWith.format(context, {
              'topic': selected.title.of(context),
            }),
      icon: const Icon(Icons.help_outline),
      onPressed: enabled
          ? () => Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => selected == null
                    ? const HelpScreen()
                    : HelpTopicScreen(topic: selected),
              ),
            )
          : null,
    );
  }
}
