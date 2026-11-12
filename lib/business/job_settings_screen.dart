import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../automation/automation_screen.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'settings_api.dart';
import 'settings_screen.dart';
import 'lifecycle_api.dart';
import 'job_deletion_dialog.dart';
import 'tax_rate.dart';

class JobSettingsScreen extends ConsumerWidget {
  const JobSettingsScreen({super.key, required this.jobId});
  final String jobId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final job = ref.watch(jobPreferencesProvider(jobId));
    final business = ref.watch(businessSettingsProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Job settings'),
        actions: [
          IconButton(
            tooltip: 'Refresh job details and deletion eligibility',
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.invalidate(jobPreferencesProvider(jobId)),
          ),
        ],
      ),
      body: job.when(
        skipLoadingOnReload: true,
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: FilledButton(
            onPressed: () => ref.invalidate(jobPreferencesProvider(jobId)),
            child: const Text('Retry job settings'),
          ),
        ),
        data: (preferences) => business.when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: FilledButton(
              onPressed: () => ref.invalidate(businessSettingsProvider),
              child: const Text('Retry customers'),
            ),
          ),
          data: (data) => _JobForm(initial: preferences, business: data),
        ),
      ),
    );
  }
}

class _JobForm extends ConsumerStatefulWidget {
  const _JobForm({required this.initial, required this.business});
  final JobPreferences initial;
  final BusinessSettings business;
  @override
  ConsumerState<_JobForm> createState() => _JobFormState();
}

class _JobFormState extends ConsumerState<_JobForm> {
  late final _name = TextEditingController(text: widget.initial.name);
  late final _rate = TextEditingController(
    text: money(widget.initial.rate, widget.initial.digits),
  );
  late final _email = TextEditingController(
    text: widget.initial.effectiveEmail,
  );
  late final _tax = TextEditingController(
    text: taxPercent(
      widget.initial.taxUnits ?? widget.initial.effectiveTaxUnits,
    ),
  );
  late bool _inheritTax = widget.initial.taxUnits == null;
  String? _taxError;
  late String _mode = widget.initial.mode;
  late String? _customer = widget.initial.customerId;
  late bool _useCustomerEmail = widget.initial.emailOverride == null;
  late int _color = widget.initial.color;
  @override
  void dispose() {
    _name.dispose();
    _rate.dispose();
    _email.dispose();
    _tax.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final data = ref.watch(jobPreferencesProvider(widget.initial.id)).value;
    final customers =
        ref.watch(businessSettingsProvider).value?.customers ??
        widget.business.customers;
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        TextField(
          controller: _name,
          maxLength: 240,
          decoration: const InputDecoration(labelText: 'Job name'),
          readOnly: data?.archived ?? true,
        ),
        const SizedBox(height: 16),
        Text('Job color', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 12),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            for (final color in {
              0xff176b55,
              0xff2563eb,
              0xff7c3aed,
              0xffb45309,
              0xffbe123c,
              0xff475569,
              _color,
            })
              Semantics(
                selected: color == _color,
                label: 'Job color ${color.toRadixString(16)}',
                child: IconButton.filled(
                  tooltip: color == _color
                      ? 'Selected job color'
                      : 'Choose job color',
                  style: IconButton.styleFrom(
                    backgroundColor: Color(color),
                    foregroundColor: Colors.white,
                  ),
                  onPressed: data?.archived == false
                      ? () => setState(() => _color = color)
                      : null,
                  icon: Icon(
                    color == _color ? Icons.check : Icons.circle_outlined,
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 32),
        Text('Billing basis', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 16),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(
              value: 'HOURLY',
              label: Text('Hourly'),
              icon: Icon(Icons.schedule),
            ),
            ButtonSegment(
              value: 'TASK',
              label: Text('Per task'),
              icon: Icon(Icons.task_alt),
            ),
          ],
          selected: {_mode},
          onSelectionChanged: data?.archived == false
              ? (v) => setState(() => _mode = v.single)
              : null,
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Text(
            _mode == 'TASK'
                ? 'Completed billable task prices determine the amount owed. Recorded time remains available.'
                : 'Finalized report time × hourly rate determines the amount owed. Task prices are kept separately.',
          ),
        ),
        TextField(
          controller: _rate,
          readOnly: data?.archived ?? true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Hourly rate override (${widget.initial.currency})',
            helperText: 'Blank uses the global default for the same currency.',
          ),
        ),
        const SizedBox(height: 24),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Use default tax rate'),
          subtitle: Text(
            '${taxPercent(ref.watch(businessSettingsProvider).value?.settings.defaultTaxUnits ?? widget.business.settings.defaultTaxUnits)}% of the invoice subtotal',
          ),
          value: _inheritTax,
          onChanged: data?.archived == false
              ? (value) => setState(() {
                  _inheritTax = value;
                  _taxError = null;
                })
              : null,
        ),
        if (!_inheritTax)
          Semantics(
            identifier: 'job-tax-percent',
            child: TextField(
              controller: _tax,
              readOnly: data?.archived ?? true,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              maxLength: 32,
              maxLengthEnforcement: MaxLengthEnforcement.none,
              decoration: InputDecoration(
                labelText: 'Job tax (%)',
                helperText: 'Enter 0 for no tax on this job. Issued invoices keep their original tax.',
                helperMaxLines: 3,
                errorText: _taxError,
                errorMaxLines: 3,
              ),
              onChanged: (_) {
                if (_taxError != null) {
                  setState(() => _taxError = taxPercentError(_tax.text));
                }
              },
            ),
          ),
        const SizedBox(height: 32),
        Text(
          'Customer & invoice recipient',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: _customer ?? '',
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Customer'),
          items: [
            const DropdownMenuItem(value: '', child: Text('No customer')),
            for (final c in customers.where(
              (c) => !c.archived || c.id == _customer,
            ))
              DropdownMenuItem(
                value: c.id,
                child: Text('${c.name}${c.archived ? ' (archived)' : ''}'),
              ),
          ],
          onChanged: data?.archived == false
              ? (v) => setState(() {
                  _customer = v == '' ? null : v;
                  if (_useCustomerEmail) {
                    _email.text =
                        customers
                            .where((c) => c.id == _customer)
                            .firstOrNull
                            ?.email ??
                        '';
                  }
                })
              : null,
        ),
        TextButton.icon(
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute<void>(builder: (_) => const CustomersScreen()),
          ),
          icon: const Icon(Icons.people_outline),
          label: const Text('Manage customers'),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Use customer email'),
          subtitle: const Text(
            'Turn off to set a recipient for only this job.',
          ),
          value: _useCustomerEmail,
          onChanged: data?.archived == false
              ? (v) => setState(() {
                  _useCustomerEmail = v;
                  if (v) {
                    _email.text =
                        customers
                            .where((c) => c.id == _customer)
                            .firstOrNull
                            ?.email ??
                        '';
                  }
                })
              : null,
        ),
        TextField(
          controller: _email,
          readOnly: _useCustomerEmail || (data?.archived ?? true),
          keyboardType: TextInputType.emailAddress,
          decoration: const InputDecoration(
            labelText: 'Customer email for this job',
          ),
          maxLength: 254,
        ),
        const SizedBox(height: 24),
        BusinessCommand(
          label: 'Save job settings',
          enabled: data?.archived == false,
          create: () {
            if (_name.text.trim().isEmpty) {
              throw const FormatException('Enter a job name.');
            }
            final taxError = _inheritTax ? null : taxPercentError(_tax.text);
            setState(() => _taxError = taxError);
            if (taxError != null) throw FormatException(taxError);
            final id = newBusinessId();
            final p = JobPreferences(
              version: data!.version,
              id: data.id,
              name: _name.text.trim(),
              color: _color,
              mode: _mode,
              currency: data.currency,
              digits: data.digits,
              archived: data.archived,
              effectiveEmail: data.effectiveEmail,
              rate: parseMoney(_rate.text, data.digits),
              customerId: _customer,
              emailOverride: _useCustomerEmail ? null : _email.text.trim(),
              taxUnits: _inheritTax ? null : parseTaxPercent(_tax.text),
              effectiveTaxUnits: data.effectiveTaxUnits,
            );
            return () => ref.read(businessSettingsApiProvider).saveJob(id, p);
          },
          onCommitted: () {
            ref.invalidate(jobPreferencesProvider(widget.initial.id));
            ref.invalidate(boardProvider(widget.initial.id));
            Navigator.pop(context);
          },
        ),
        const SizedBox(height: 32),
        if (data?.archived == false)
          OutlinedButton.icon(
            icon: const Icon(Icons.archive_outlined),
            label: const Text('Archive job'),
            onPressed: () => showDialog<void>(
              context: context,
              barrierDismissible: false,
              builder: (dialog) => AlertDialog(
                title: const Text('Archive this job?'),
                content: const Text(
                  'Clock out first. History and automation preferences are kept. You can restore the job; permanent deletion requires 30 continuous days archived.',
                ),
                actions: [
                  BusinessCommand(
                    label: 'Archive job',
                    create: () {
                      final id = newBusinessId();
                      final version = data!.version;
                      final api = ref.read(lifecycleApiProvider);
                      return () => api.archive(id, version, data.id);
                    },
                    onCommitted: () {
                      ref.invalidate(archiveProvider);
                      Navigator.pop(dialog);
                      Navigator.pop(context);
                    },
                  ),
                ],
              ),
            ),
          ),
        if (data?.archived == true)
          BusinessCommand(
            label: 'Restore job',
            create: () {
              final id = newBusinessId();
              final api = ref.read(lifecycleApiProvider);
              return () => api.restore(id, data!.version, data.id);
            },
            onCommitted: () {
              ref.invalidate(archiveProvider);
              ref.invalidate(jobPreferencesProvider(widget.initial.id));
            },
          ),
        if (data?.archived == true && data?.canDelete == true)
          Padding(
            padding: const EdgeInsets.only(top: 24),
            child: OutlinedButton.icon(
              style: OutlinedButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              icon: const Icon(Icons.delete_forever_outlined),
              label: const Text('Permanently delete job'),
              onPressed: () async {
                final navigator = Navigator.of(context);
                final container = ProviderScope.containerOf(
                  context,
                  listen: false,
                );
                final api = ref.read(lifecycleApiProvider);
                final deleted = await confirmJobDeletion(
                  context,
                  api,
                  data!.version,
                  data.id,
                  data.name,
                );
                if (deleted == true) {
                  container.invalidate(archiveProvider);
                  if (navigator.mounted) {
                    navigator.popUntil((route) => route.isFirst);
                  }
                }
              },
            ),
          ),
        const SizedBox(height: 24),
        OutlinedButton.icon(
          onPressed: data?.archived != false
              ? null
              : () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => AutomationScreen(
                      jobId: widget.initial.id,
                      jobName: data?.name ?? widget.initial.name,
                    ),
                  ),
                ),
          icon: const Icon(Icons.location_on_outlined),
          label: const Text('Worksites & automation'),
        ),
      ],
    );
  }
}
