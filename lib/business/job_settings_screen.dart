import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../automation/automation_screen.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'settings_api.dart';
import 'settings_screen.dart';

class JobSettingsScreen extends ConsumerWidget {
  const JobSettingsScreen({super.key, required this.jobId});
  final String jobId;
  @override Widget build(BuildContext context, WidgetRef ref) {
    final job = ref.watch(jobPreferencesProvider(jobId));
    final business = ref.watch(businessSettingsProvider);
    return Scaffold(appBar: AppBar(title: const Text('Job settings')), body: job.when(loading: () => const Center(child: CircularProgressIndicator()), error: (_, _) => Center(child: FilledButton(onPressed: () => ref.invalidate(jobPreferencesProvider(jobId)), child: const Text('Retry job settings'))), data: (preferences) => business.when(loading: () => const Center(child: CircularProgressIndicator()), error: (_, _) => Center(child: FilledButton(onPressed: () => ref.invalidate(businessSettingsProvider), child: const Text('Retry customers'))), data: (data) => _JobForm(initial: preferences, business: data))));
  }
}
class _JobForm extends ConsumerStatefulWidget {
  const _JobForm({required this.initial, required this.business});
  final JobPreferences initial;
  final BusinessSettings business;
  @override ConsumerState<_JobForm> createState() => _JobFormState();
}
class _JobFormState extends ConsumerState<_JobForm> {
  late final _name = TextEditingController(text: widget.initial.name);
  late final _rate = TextEditingController(text: money(widget.initial.rate, widget.initial.digits));
  late final _email = TextEditingController(text: widget.initial.effectiveEmail);
  late String _mode = widget.initial.mode;
  late String? _customer = widget.initial.customerId;
  late bool _useCustomerEmail = widget.initial.emailOverride == null;
  late int _color = widget.initial.color;
  @override void dispose() { _name.dispose(); _rate.dispose(); _email.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) {
    final data = ref.watch(jobPreferencesProvider(widget.initial.id)).asData?.value;
    final customers = ref.watch(businessSettingsProvider).asData?.value.customers ?? widget.business.customers;
    return ListView(padding: const EdgeInsets.all(24), children: [
      TextField(controller: _name, maxLength: 240, decoration: const InputDecoration(labelText: 'Job name'), readOnly: data?.archived ?? true),
      const SizedBox(height: 16), Text('Job color', style: Theme.of(context).textTheme.titleMedium), const SizedBox(height: 12),
      Wrap(spacing: 12, runSpacing: 12, children: [for (final color in {0xff176b55, 0xff2563eb, 0xff7c3aed, 0xffb45309, 0xffbe123c, 0xff475569, _color}) Semantics(selected: color == _color, label: 'Job color ${color.toRadixString(16)}', child: IconButton.filled(tooltip: color == _color ? 'Selected job color' : 'Choose job color', style: IconButton.styleFrom(backgroundColor: Color(color), foregroundColor: Colors.white), onPressed: data?.archived == false ? () => setState(() => _color = color) : null, icon: Icon(color == _color ? Icons.check : Icons.circle_outlined)))]),
      const SizedBox(height: 32), Text('Billing basis', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 16),
      SegmentedButton<String>(segments: const [ButtonSegment(value: 'HOURLY', label: Text('Hourly'), icon: Icon(Icons.schedule)), ButtonSegment(value: 'TASK', label: Text('Per task'), icon: Icon(Icons.task_alt))], selected: {_mode}, onSelectionChanged: data?.archived == false ? (v) => setState(() => _mode = v.single) : null),
      Padding(padding: const EdgeInsets.symmetric(vertical: 16), child: Text(_mode == 'TASK' ? 'Completed billable task prices determine the amount owed. Recorded time remains available.' : 'Finalized report time × hourly rate determines the amount owed. Task prices are kept separately.')),
      TextField(controller: _rate, readOnly: data?.archived ?? true, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: InputDecoration(labelText: 'Hourly rate override (${widget.initial.currency})', helperText: 'Blank uses the global default for the same currency.')),
      const SizedBox(height: 32), Text('Customer & invoice recipient', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 16),
      DropdownButtonFormField<String>(initialValue: _customer ?? '', isExpanded: true, decoration: const InputDecoration(labelText: 'Customer'), items: [const DropdownMenuItem(value: '', child: Text('No customer')), for (final c in customers.where((c) => !c.archived || c.id == _customer)) DropdownMenuItem(value: c.id, child: Text('${c.name}${c.archived ? ' (archived)' : ''}'))], onChanged: data?.archived == false ? (v) => setState(() { _customer = v == '' ? null : v; if (_useCustomerEmail) _email.text = customers.where((c) => c.id == _customer).firstOrNull?.email ?? ''; }) : null),
      TextButton.icon(onPressed: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => const CustomersScreen())), icon: const Icon(Icons.people_outline), label: const Text('Manage customers')),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Use customer email'), subtitle: const Text('Turn off to set a recipient for only this job.'), value: _useCustomerEmail, onChanged: data?.archived == false ? (v) => setState(() { _useCustomerEmail = v; if (v) _email.text = customers.where((c) => c.id == _customer).firstOrNull?.email ?? ''; }) : null),
      TextField(controller: _email, readOnly: _useCustomerEmail || (data?.archived ?? true), keyboardType: TextInputType.emailAddress, decoration: const InputDecoration(labelText: 'Customer email for this job'), maxLength: 254),
      const SizedBox(height: 24), BusinessCommand(label: 'Save job settings', enabled: data?.archived == false, create: () { if (_name.text.trim().isEmpty) { throw const FormatException('Enter a job name.'); } final id = newBusinessId(); final p = JobPreferences(version: data!.version, id: data.id, name: _name.text.trim(), color: _color, mode: _mode, currency: data.currency, digits: data.digits, archived: data.archived, effectiveEmail: data.effectiveEmail, rate: parseMoney(_rate.text, data.digits), customerId: _customer, emailOverride: _useCustomerEmail ? null : _email.text.trim()); return () => ref.read(businessSettingsApiProvider).saveJob(id, p); }, onCommitted: () { ref.invalidate(jobPreferencesProvider(widget.initial.id)); ref.invalidate(boardProvider(widget.initial.id)); Navigator.pop(context); }),
      const SizedBox(height: 24), OutlinedButton.icon(onPressed: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => AutomationScreen(jobId: widget.initial.id, jobName: data?.name ?? widget.initial.name))), icon: const Icon(Icons.location_on_outlined), label: const Text('Worksites & automation')),
    ]);
  }
}
