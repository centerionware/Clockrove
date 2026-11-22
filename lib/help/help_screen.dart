import 'package:flutter/material.dart';

enum HelpTopic {
  timekeeping(
    'Start and review work',
    'Create a job, then start its timer from the work screen. Stop the timer '
        'when you finish. Use job work history to add a missed entry or '
        'correct the time. The manual timer works without location access.',
  ),
  automation(
    'Location and site automation',
    'Open a job’s settings to add a work site and enable automation. '
        'Allow precise location and, where Android requires it, background '
        'location. Check the site monitoring status if a transition is missed. '
        'Once monitoring is ready, leave the site and return for a new arrival. '
        'You can clock in or out manually while troubleshooting.',
  ),
  backups(
    'Back up and restore your records',
    'In Settings, choose Export encrypted backup and save the file somewhere '
        'you can access later. Keep the password separately. To restore, stop '
        'your timer, choose Restore encrypted backup, and enter the password. '
        'Review the proposed merge and resolve any conflicts before applying.',
  ),
  locationHistory(
    'Control saved location history',
    'Work location recording controls whether Clockrove saves locations '
        'during a session. Site automation has its own setting. Use the '
        'location history deletion control to remove saved GPS points from '
        'the app. Copies you already exported remain at their destination.',
  );

  const HelpTopic(this.title, this.content);

  final String title;
  final String content;
}

class HelpScreen extends StatefulWidget {
  const HelpScreen({super.key});

  @override
  State<HelpScreen> createState() => _HelpScreenState();
}

class _HelpScreenState extends State<HelpScreen> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final topics = HelpTopic.values.where(
      (topic) =>
          topic.title.toLowerCase().contains(_query) ||
          topic.content.toLowerCase().contains(_query),
    );
    return Scaffold(
      appBar: AppBar(title: const Text('Help')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            decoration: const InputDecoration(
              labelText: 'Search help',
              prefixIcon: Icon(Icons.search),
            ),
            onChanged: (value) =>
                setState(() => _query = value.trim().toLowerCase()),
          ),
          const SizedBox(height: 12),
          if (topics.isEmpty) const Text('No matching help topics.'),
          for (final topic in topics)
            ListTile(
              title: Text(topic.title),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => HelpTopicScreen(topic: topic),
                ),
              ),
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
    appBar: AppBar(title: const Text('Help')),
    body: ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text(topic.title, style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 12),
        Text(topic.content, style: Theme.of(context).textTheme.bodyLarge),
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
      tooltip: selected == null ? 'Help' : 'Help with ${selected.title}',
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
