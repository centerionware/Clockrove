import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/app_localizations.dart';

enum HelpTopic {
  timekeeping(
    AppMessage.helpTimekeeping,
    AppMessage.helpTimekeepingBody,
  ),
  automation(
    AppMessage.helpAutomation,
    AppMessage.helpAutomationBody,
  ),
  backups(
    AppMessage.helpBackups,
    AppMessage.helpBackupsBody,
  ),
  boards(
    AppMessage.helpBoards,
    AppMessage.helpBoardsBody,
  ),
  reports(
    AppMessage.helpReports,
    AppMessage.helpReportsBody,
  ),
  billing(
    AppMessage.helpBilling,
    AppMessage.helpBillingBody,
  ),
  widgets(
    AppMessage.helpWidgets,
    AppMessage.helpWidgetsBody,
  ),
  locationHistory(
    AppMessage.helpLocationHistory,
    AppMessage.helpLocationHistoryBody,
  );

  const HelpTopic(this.title, this.bodyMessage);

  final AppMessage title;
  final AppMessage bodyMessage;

  String get content => bodyMessage.english;
  String body(BuildContext context) => bodyMessage.of(context);
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
