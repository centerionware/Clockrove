import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'settings_api.dart';
import 'logo_controls.dart';
import 'email_template.dart';
import 'tax_rate.dart';
import 'invoice_style_preview_screen.dart';
import 'invoice_billing_policy.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: const Text('Settings')),
    body: ref
        .watch(businessSettingsProvider)
        .when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: FilledButton(
              onPressed: () => ref.invalidate(businessSettingsProvider),
              child: const Text('Retry settings'),
            ),
          ),
          data: (data) => _CompanyForm(initial: data.settings),
        ),
  );
}

class _CompanyForm extends ConsumerStatefulWidget {
  const _CompanyForm({required this.initial});
  final CompanySettings initial;
  @override
  ConsumerState<_CompanyForm> createState() => _CompanyFormState();
}

class _CompanyFormState extends ConsumerState<_CompanyForm> {
  bool _logoBusy = false;
  String? _subjectError,
      _bodyError,
      _taxError,
      _receivablesError,
      _minimumHoursErrorText,
      _minimumTaskFeeError;
  late final _tax = TextEditingController(
    text: taxPercent(widget.initial.defaultTaxUnits),
  );
  late final _rate = TextEditingController(
    text: money(widget.initial.defaultHourlyMinor, widget.initial.digits),
  );
  late final _minimumHours = TextEditingController(
    text: widget.initial.minimumBillableHours,
  );
  late final _minimumTaskFee = TextEditingController(
    text: money(widget.initial.minimumTaskFeeMinor, widget.initial.digits),
  );
  late final _name = TextEditingController(text: widget.initial.companyName);
  late final _email = TextEditingController(text: widget.initial.companyEmail);
  late final _receivables = TextEditingController(
    text: widget.initial.receivablesEmail,
  );
  late final _phone = TextEditingController(text: widget.initial.companyPhone);
  late final _address = TextEditingController(
    text: widget.initial.companyAddress,
  );
  late final _website = TextEditingController(
    text: widget.initial.companyWebsite,
  );
  late final _header = TextEditingController(
    text: widget.initial.invoiceHeader,
  );
  late final _footer = TextEditingController(
    text: widget.initial.invoiceFooter,
  );
  late final _subject = TextEditingController(
    text: widget.initial.emailSubject,
  );
  late final _body = TextEditingController(text: widget.initial.emailBody);
  late String _currency = widget.initial.currency,
      _style = widget.initial.invoiceStyle;
  late String _hourlyBillingMode = widget.initial.hourlyBillingMode;
  late int _digits = widget.initial.digits;
  late bool _defaultLocationRecording = widget.initial.defaultLocationRecording;
  @override
  void dispose() {
    for (final c in [
      _rate,
      _minimumHours,
      _minimumTaskFee,
      _tax,
      _name,
      _email,
      _receivables,
      _phone,
      _address,
      _website,
      _header,
      _footer,
      _subject,
      _body,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  Widget _field(
    TextEditingController c,
    String label, {
    int lines = 1,
    int length = 4000,
    String? error,
    ValueChanged<String>? onChanged,
    bool email = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: TextField(
      controller: c,
      keyboardType: email ? TextInputType.emailAddress : null,
      autocorrect: !email,
      enableSuggestions: !email,
      decoration: InputDecoration(
        labelText: label,
        errorText: error,
        errorMaxLines: 4,
      ),
      onChanged: onChanged,
      minLines: lines,
      maxLines: lines == 1 ? 1 : lines + 4,
      maxLength: length,
    ),
  );
  void _templateChanged(String _) {
    if (_subjectError != null || _bodyError != null) {
      setState(() {
        _subjectError = emailTemplateError(_subject.text, subject: true);
        _bodyError = emailTemplateError(_body.text, subject: false);
      });
    }
  }

  String? _taskFeeError() {
    if (_minimumTaskFee.text.trim().isEmpty) return null;
    try {
      parseMoney(_minimumTaskFee.text, _digits);
      return null;
    } on FormatException {
      return 'Enter a valid amount in $_currency, or enter 0 to disable the minimum.';
    }
  }

  void _validateDraft() {
    final subject = emailTemplateError(_subject.text, subject: true);
    final body = emailTemplateError(_body.text, subject: false);
    final tax = taxPercentError(_tax.text);
    final receivables = accountingEmailError(_receivables.text);
    final minimumHours = minimumHoursError(_minimumHours.text);
    final minimumTaskFee = _taskFeeError();
    setState(() {
      _subjectError = subject;
      _bodyError = body;
      _taxError = tax;
      _receivablesError = receivables;
      _minimumHoursErrorText = minimumHours;
      _minimumTaskFeeError = minimumTaskFee;
    });
    if (subject != null || body != null) {
      throw const FormatException(
        'Fix the highlighted email subject and body templates before saving. Use the supported placeholders shown above.',
      );
    }
    if (tax != null) throw FormatException(tax);
    if (receivables != null) throw FormatException(receivables);
    if (minimumHours != null) throw FormatException(minimumHours);
    if (minimumTaskFee != null) throw FormatException(minimumTaskFee);
  }

  @override
  Widget build(BuildContext context) {
    final current = ref.watch(businessSettingsProvider).value;
    final logo = ref.watch(logoProvider).value;
    final currencies = <String, int>{
      'USD': 2,
      'EUR': 2,
      'GBP': 2,
      'CAD': 2,
      'AUD': 2,
      'NZD': 2,
      'CHF': 2,
      'JPY': 0,
      'INR': 2,
      'ZAR': 2,
      'SEK': 2,
      'NOK': 2,
      'DKK': 2,
      'KWD': 3,
      widget.initial.currency: widget.initial.digits,
    };
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('Billing defaults', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: _currency,
          decoration: const InputDecoration(labelText: 'Currency for new jobs'),
          items: [
            for (final c in currencies.keys)
              DropdownMenuItem(value: c, child: Text(c)),
          ],
          onChanged: (v) {
            if (v != null && v != _currency) {
              setState(() {
                _currency = v;
                _digits = currencies[v]!;
                _rate.clear();
                _minimumTaskFee.clear();
              });
            }
          },
        ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Text(
            'Existing jobs keep their currency. Changing currency clears the default rate and per-task minimum; enter new amounts explicitly.',
          ),
        ),
        TextField(
          controller: _rate,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Default hourly rate ($_currency)',
            helperText: 'Blank means no rate set. Override this in any job.',
          ),
        ),
        const SizedBox(height: 24),
        Text(
          'Invoice billing rules',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 8),
        const Text(
          'These rules affect new invoices only. Work reports and recorded time stay exact.',
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: _hourlyBillingMode,
          decoration: const InputDecoration(labelText: 'Hourly billing rule'),
          items: const [
            DropdownMenuItem(
              value: minimumThenExactBilling,
              child: Text('Minimum hours, then exact time'),
            ),
            DropdownMenuItem(
              value: roundTotalUpBilling,
              child: Text('Round total time up to the next hour'),
            ),
          ],
          onChanged: (value) {
            if (value != null) setState(() => _hourlyBillingMode = value);
          },
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _minimumHours,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Minimum billable hours',
            helperText:
                '0 disables the minimum. Applied only to hourly invoices.',
            errorText: _minimumHoursErrorText,
          ),
          onChanged: (_) {
            if (_minimumHoursErrorText != null) {
              setState(
                () => _minimumHoursErrorText = minimumHoursError(
                  _minimumHours.text,
                ),
              );
            }
          },
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _minimumTaskFee,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Minimum per-task invoice fee ($_currency)',
            helperText: '0 disables it. A job in another currency needs its own override.',
            errorText: _minimumTaskFeeError,
          ),
          onChanged: (_) {
            if (_minimumTaskFeeError != null) {
              setState(() => _minimumTaskFeeError = _taskFeeError());
            }
          },
        ),
        const SizedBox(height: 16),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Record work locations'),
          subtitle: const Text(
            'Default on. Samples target about every 10 minutes while clocked in; delivery varies. Turning this off stops future samples and keeps saved history. Each job can override it.',
          ),
          value: _defaultLocationRecording,
          onChanged: (value) =>
              setState(() => _defaultLocationRecording = value),
        ),
        const SizedBox(height: 20),
        Semantics(
          identifier: 'default-tax-percent',
          child: TextField(
            controller: _tax,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            maxLength: 32,
            maxLengthEnforcement: MaxLengthEnforcement.none,
            decoration: InputDecoration(
              labelText: 'Default tax (%)',
              helperText: '7.5 adds 7.5% to the subtotal. Override per job; 0 means no tax.',
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
          'Company & contact',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        _field(_name, 'Company name', length: 240),
        _field(_email, 'Company email', length: 254, email: true),
        Semantics(
          identifier: 'accounts-receivable-email',
          child: _field(
            _receivables,
            'Accounts receivable email (optional)',
            length: 254,
            email: true,
            error: _receivablesError,
            onChanged: (_) {
              if (_receivablesError != null) {
                setState(
                  () => _receivablesError = accountingEmailError(
                    _receivables.text,
                  ),
                );
              }
            },
          ),
        ),
        const Padding(
          padding: EdgeInsets.only(bottom: 16),
          child: Text(
            'Invoice and receipt emails CC this address, or the company email when blank.',
          ),
        ),
        _field(_phone, 'Phone', length: 120),
        _field(_address, 'Address', lines: 2),
        _field(_website, 'Website', length: 2000),
        const SizedBox(height: 16),
        Text(
          'Invoice appearance',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: _style,
          decoration: const InputDecoration(labelText: 'Invoice style'),
          items: const [
            DropdownMenuItem(value: 'MINIMAL', child: Text('Minimal')),
            DropdownMenuItem(value: 'CLASSIC', child: Text('Classic')),
            DropdownMenuItem(value: 'MODERN', child: Text('Modern')),
          ],
          onChanged: (v) {
            if (v != null) {
              setState(() => _style = v);
            }
          },
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            key: const ValueKey('invoice-style-preview'),
            onPressed: () => Navigator.push<void>(
              context,
              MaterialPageRoute<void>(
                builder: (_) => InvoiceStylePreviewScreen(
                  style: _style,
                  currency: _currency,
                  digits: _digits,
                  logo: logo?.png,
                ),
              ),
            ),
            icon: const Icon(Icons.visibility_outlined),
            label: const Text('Preview invoice'),
          ),
        ),
        const SizedBox(height: 20),
        LogoControls(
          onBusyChanged: (value) {
            if (mounted) {
              setState(() => _logoBusy = value);
            }
          },
        ),
        const SizedBox(height: 20),
        _field(_header, 'Optional invoice header', lines: 2),
        _field(_footer, 'Optional invoice footer', lines: 2),
        Text('Email draft', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 16),
        const Text(
          'Placeholders: {company_name}, {customer_name}, {job_name}, {invoice_number}, {amount}, {currency}. The email app lets you review the attached invoice before sending.',
        ),
        const SizedBox(height: 16),
        _field(
          _subject,
          'Email subject template',
          length: 500,
          error: _subjectError,
          onChanged: _templateChanged,
        ),
        _field(
          _body,
          'Email body template',
          lines: 5,
          length: 20000,
          error: _bodyError,
          onChanged: _templateChanged,
        ),
        BusinessCommand(
          label: 'Save settings',
          enabled: current != null && !_logoBusy,
          create: () {
            _validateDraft();
            final id = newBusinessId();
            final version = current!.version;
            final s = CompanySettings(
              currency: _currency,
              digits: _digits,
              defaultHourlyMinor: parseMoney(_rate.text, _digits),
              defaultTaxUnits: parseTaxPercent(_tax.text),
              companyName: _name.text.trim(),
              companyEmail: _email.text.trim(),
              receivablesEmail: _receivables.text.trim(),
              companyPhone: _phone.text,
              companyAddress: _address.text,
              companyWebsite: _website.text,
              invoiceHeader: _header.text,
              invoiceFooter: _footer.text,
              invoiceStyle: _style,
              emailSubject: _subject.text,
              emailBody: _body.text,
              defaultLocationRecording: _defaultLocationRecording,
              hourlyBillingMode: _hourlyBillingMode,
              minimumBillableHours: canonicalMinimumHours(
                _minimumHours.text.trim().isEmpty ? '0' : _minimumHours.text,
              ),
              minimumTaskFeeMinor:
                  parseMoney(_minimumTaskFee.text, _digits) ?? '0',
            );
            return () =>
                ref.read(businessSettingsApiProvider).settings(id, version, s);
          },
          onCommitted: () {
            ref.invalidate(businessSettingsProvider);
            Navigator.pop(context);
          },
        ),
      ],
    );
  }
}

class CustomersScreen extends ConsumerWidget {
  const CustomersScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: const Text('Customers')),
    floatingActionButton: FloatingActionButton.extended(
      elevation: 0,
      focusElevation: 0,
      hoverElevation: 0,
      highlightElevation: 0,
      onPressed: () => Navigator.push(
        context,
        MaterialPageRoute<void>(builder: (_) => const CustomerEditor()),
      ),
      icon: const Icon(Icons.person_add_alt),
      label: const Text('Customer'),
    ),
    body: ref
        .watch(businessSettingsProvider)
        .when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: TextButton(
              onPressed: () => ref.invalidate(businessSettingsProvider),
              child: const Text('Retry customers'),
            ),
          ),
          data: (data) => data.customers.isEmpty
              ? const Center(
                  child: Text(
                    'Add a customer to keep billing details with your jobs.',
                  ),
                )
              : ListView(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 100),
                  children: [
                    for (final c in data.customers)
                      Card(
                        child: ListTile(
                          leading: CircleAvatar(
                            child: Icon(
                              c.archived
                                  ? Icons.archive_outlined
                                  : Icons.person_outline,
                            ),
                          ),
                          title: Text(c.name),
                          subtitle: Text(
                            [
                              if (c.archived) 'Archived',
                              if (c.email.isNotEmpty) c.email,
                              if (c.phone.isNotEmpty) c.phone,
                            ].join(' · '),
                          ),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute<void>(
                              builder: (_) => CustomerEditor(customer: c),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
        ),
  );
}

class CustomerEditor extends ConsumerStatefulWidget {
  const CustomerEditor({super.key, this.customer});
  final Customer? customer;
  @override
  ConsumerState<CustomerEditor> createState() => _CustomerEditorState();
}

class _CustomerEditorState extends ConsumerState<CustomerEditor> {
  late final _id = widget.customer?.id ?? newBusinessId();
  late final _name = TextEditingController(text: widget.customer?.name ?? '');
  late final _email = TextEditingController(text: widget.customer?.email ?? '');
  late final _phone = TextEditingController(text: widget.customer?.phone ?? '');
  late final _address = TextEditingController(
    text: widget.customer?.address ?? '',
  );
  late final _notes = TextEditingController(text: widget.customer?.notes ?? '');
  late bool _archived = widget.customer?.archived ?? false;
  @override
  void dispose() {
    for (final c in [_name, _email, _phone, _address, _notes]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final data = ref.watch(businessSettingsProvider).value;
    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.customer == null ? 'New customer' : 'Customer details',
        ),
      ),
      bottomNavigationBar: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 12, 24, 12),
          child: BusinessCommand(
            key: ValueKey('save-customer/$_id'),
            label: 'Save customer',
            enabled: data != null,
            create: () {
              if (_name.text.trim().isEmpty) {
                throw const FormatException('Enter a customer name.');
              }
              final id = newBusinessId();
              final version = data!.version;
              final c = Customer(
                id: _id,
                name: _name.text.trim(),
                email: _email.text.trim(),
                phone: _phone.text,
                address: _address.text,
                notes: _notes.text,
                archived: _archived,
              );
              return () => ref
                  .read(businessSettingsApiProvider)
                  .customer(id, version, c);
            },
            onCommitted: () {
              ref.invalidate(businessSettingsProvider);
              Navigator.pop(context);
            },
          ),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          for (final (controller, label, lines, length) in [
            (_name, 'Customer name', 1, 240),
            (_email, 'Email', 1, 254),
            (_phone, 'Phone', 1, 120),
            (_address, 'Billing address', 3, 4000),
            (_notes, 'Notes', 3, 20000),
          ])
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: TextField(
                controller: controller,
                decoration: InputDecoration(labelText: label),
                minLines: lines,
                maxLines: lines + 2,
                maxLength: length,
              ),
            ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Archive customer'),
            subtitle: const Text(
              'Keep existing job contacts; hide from new assignments.',
            ),
            value: _archived,
            onChanged: (v) => setState(() => _archived = v),
          ),
          const SizedBox(height: 24),
          if (widget.customer != null) ...[
            const SizedBox(height: 32),
            OutlinedButton.icon(
              style: OutlinedButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              icon: const Icon(Icons.delete_outline),
              label: const Text('Delete unused customer'),
              onPressed: () => showDialog<void>(
                context: context,
                barrierDismissible: false,
                builder: (dialog) => AlertDialog(
                  title: const Text('Delete customer?'),
                  content: const Text(
                    'Only unused customers can be deleted. Archive a customer assigned to jobs or invoices.',
                  ),
                  actions: [
                    BusinessCommand(
                      label: 'Delete customer',
                      enabled: data != null,
                      create: () {
                        final id = newBusinessId();
                        final version = data!.version;
                        return () => ref
                            .read(businessSettingsApiProvider)
                            .removeCustomer(id, version, _id);
                      },
                      onCommitted: () {
                        ref.invalidate(businessSettingsProvider);
                        Navigator.pop(dialog);
                        Navigator.pop(context);
                      },
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
