import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'settings_api.dart';
import 'logo_controls.dart';
import 'email_template.dart';
import 'tax_rate.dart';
import 'settings_validation_messages.dart';
import 'invoice_style_preview_screen.dart';
import 'invoice_billing_policy.dart';
import '../help/help_screen.dart';
import '../l10n/app_localizations.dart';
import '../l10n/language_setting.dart';
import '../maps/map_provider_settings_screen.dart';
import '../backup/auto_backup_settings.dart';
import '../backup/backup_export_screen.dart';
import '../backup/backup_restore_screen.dart';
import '../tracking/timer_providers.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(
      title: Text(AppMessage.settings.of(context)),
      actions: const [HelpButton()],
    ),
    body: ref
        .watch(businessSettingsProvider)
        .when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: FilledButton(
              onPressed: () => ref.invalidate(businessSettingsProvider),
              child: Text(AppMessage.retrySettings.of(context)),
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
  AppMessage? _subjectError, _bodyError;
  String? _taxError,
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
  final _subject = TextEditingController();
  final _body = TextEditingController();
  String? _displayedDefaultSubject;
  String? _displayedDefaultBody;
  late String _currency = widget.initial.currency,
      _style = widget.initial.invoiceStyle;
  late String _hourlyBillingMode = widget.initial.hourlyBillingMode;
  late int _digits = widget.initial.digits;
  late bool _defaultLocationRecording = widget.initial.defaultLocationRecording;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final subjectDefault = AppMessage.invoiceEmailDefaultSubject.of(context);
    final bodyDefault = AppMessage.invoiceEmailDefaultBody.of(context);
    if (_displayedDefaultSubject == null) {
      _subject.text =
          widget.initial.emailSubject ==
              AppMessage.invoiceEmailDefaultSubject.english
          ? subjectDefault
          : widget.initial.emailSubject;
      _body.text =
          widget.initial.emailBody == AppMessage.invoiceEmailDefaultBody.english
          ? bodyDefault
          : widget.initial.emailBody;
    } else {
      if (_subject.text == _displayedDefaultSubject &&
          _displayedDefaultSubject != subjectDefault) {
        _subject.text = subjectDefault;
      }
      if (_body.text == _displayedDefaultBody &&
          _displayedDefaultBody != bodyDefault) {
        _body.text = bodyDefault;
      }
    }
    _displayedDefaultSubject = subjectDefault;
    _displayedDefaultBody = bodyDefault;
  }

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
      return AppMessage.validationTaskFee.format(context, {
        'currency': _currency,
      });
    }
  }

  void _validateDraft() {
    final subject = emailTemplateError(_subject.text, subject: true);
    final body = emailTemplateError(_body.text, subject: false);
    final tax = settingsTaxInputError(context, _tax.text);
    final receivables = settingsReceivablesInputError(
      context,
      _receivables.text,
    );
    final minimumHours = settingsHoursInputError(context, _minimumHours.text);
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
      throw FormatException(AppMessage.invoiceEmailFixTemplates.of(context));
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
        Text(
          AppMessage.backupAndRestore.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 8),
        Text(AppMessage.settingsBackupHelp.of(context)),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const ValueKey('settings-export-backup'),
          onPressed: () => Navigator.of(context).push<void>(
            MaterialPageRoute<void>(builder: (_) => const BackupExportScreen()),
          ),
          icon: const Icon(Icons.backup_outlined),
          label: Text(AppMessage.exportBackup.of(context)),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: const ValueKey('settings-restore-backup'),
          onPressed: () async {
            final applied = await Navigator.of(context).push<bool>(
              MaterialPageRoute<bool>(
                builder: (_) => const BackupRestoreScreen(),
              ),
            );
            if (applied == true && mounted) {
              ref.invalidate(timerVersionsProvider);
              ref.invalidate(timerSnapshotProvider);
              ref.invalidate(businessSettingsProvider);
            }
          },
          icon: const Icon(Icons.restore),
          label: Text(AppMessage.restoreBackup.of(context)),
        ),
        const SizedBox(height: 24),
        const LanguageSetting(),
        const SizedBox(height: 24),
        Text(
          AppMessage.billingDefaults.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: _currency,
          decoration: InputDecoration(
            labelText: AppMessage.currencyForNewJobs.of(context),
          ),
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
        Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Text(AppMessage.settingsCurrencyChangeHelp.of(context)),
        ),
        TextField(
          controller: _rate,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: AppMessage.defaultHourlyRate.format(context, {
              'currency': _currency,
            }),
            helperText: AppMessage.settingsRateBlankHelp.of(context),
          ),
        ),
        const SizedBox(height: 24),
        Text(
          AppMessage.invoiceBillingRules.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 8),
        Text(AppMessage.jobInvoiceRulesHelp.of(context)),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: _hourlyBillingMode,
          decoration: InputDecoration(
            labelText: AppMessage.hourlyBillingRule.of(context),
          ),
          items: [
            DropdownMenuItem(
              value: minimumThenExactBilling,
              child: Text(AppMessage.minimumThenExact.of(context)),
            ),
            DropdownMenuItem(
              value: roundTotalUpBilling,
              child: Text(AppMessage.roundTotalUp.of(context)),
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
            labelText: AppMessage.minimumBillableHours.of(context),
            errorText: _minimumHoursErrorText,
          ),
          onChanged: (_) {
            if (_minimumHoursErrorText != null) {
              setState(
                () => _minimumHoursErrorText = settingsHoursInputError(
                  context,
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
            labelText: AppMessage.minimumInvoiceTaskFee.format(context, {
              'currency': _currency,
            }),
            helperText: AppMessage.settingsMinimumTaskFeeHelp.of(context),
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
          title: Text(AppMessage.recordWorkLocations.of(context)),
          subtitle: Text(AppMessage.locationRecordingDefaultHelp.of(context)),
          value: _defaultLocationRecording,
          onChanged: (value) =>
              setState(() => _defaultLocationRecording = value),
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            key: const ValueKey('open-map-provider-settings'),
            onPressed: () => Navigator.push<void>(
              context,
              MaterialPageRoute<void>(
                builder: (_) => const MapProviderSettingsScreen(),
              ),
            ),
            icon: const Icon(Icons.map_outlined),
            label: Text(AppMessage.mapProviderSettings.of(context)),
          ),
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
              labelText: AppMessage.defaultTax.of(context),
              helperText: AppMessage.settingsDefaultTaxHelp.of(context),
              helperMaxLines: 3,
              errorText: _taxError,
              errorMaxLines: 3,
            ),
            onChanged: (_) {
              if (_taxError != null) {
                setState(
                  () => _taxError = settingsTaxInputError(context, _tax.text),
                );
              }
            },
          ),
        ),
        const SizedBox(height: 32),
        Text(
          AppMessage.companyContact.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        _field(_name, AppMessage.companyName.of(context), length: 240),
        _field(
          _email,
          AppMessage.companyEmail.of(context),
          length: 254,
          email: true,
        ),
        Semantics(
          identifier: 'accounts-receivable-email',
          child: _field(
            _receivables,
            AppMessage.receivablesEmailOptional.of(context),
            length: 254,
            email: true,
            error: _receivablesError,
            onChanged: (_) {
              if (_receivablesError != null) {
                setState(
                  () => _receivablesError = settingsReceivablesInputError(
                    context,
                    _receivables.text,
                  ),
                );
              }
            },
          ),
        ),
        Padding(
          padding: EdgeInsets.only(bottom: 16),
          child: Text(AppMessage.customerReceivablesHelp.of(context)),
        ),
        _field(_phone, AppMessage.phone.of(context), length: 120),
        _field(_address, AppMessage.address.of(context), lines: 2),
        _field(_website, AppMessage.website.of(context), length: 2000),
        const SizedBox(height: 16),
        Text(
          AppMessage.invoiceStyle.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: _style,
          decoration: InputDecoration(
            labelText: AppMessage.invoiceStyle.of(context),
          ),
          items: [
            DropdownMenuItem(
              value: 'MINIMAL',
              child: Text(AppMessage.invoiceStyleMinimal.of(context)),
            ),
            DropdownMenuItem(
              value: 'CLASSIC',
              child: Text(AppMessage.invoiceStyleClassic.of(context)),
            ),
            DropdownMenuItem(
              value: 'MODERN',
              child: Text(AppMessage.invoiceStyleModern.of(context)),
            ),
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
            label: Text(AppMessage.previewInvoice.of(context)),
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
        _field(_header, AppMessage.invoiceHeaderOptional.of(context), lines: 2),
        _field(_footer, AppMessage.invoiceFooterOptional.of(context), lines: 2),
        Text(
          AppMessage.invoiceEmailDraft.of(context),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 16),
        Text(AppMessage.invoiceEmailInstructions.of(context)),
        const SizedBox(height: 16),
        _field(
          _subject,
          AppMessage.invoiceEmailSubjectLabel.of(context),
          length: 500,
          error: _subjectError?.of(context),
          onChanged: _templateChanged,
        ),
        _field(
          _body,
          AppMessage.invoiceEmailBodyLabel.of(context),
          lines: 5,
          length: 20000,
          error: _bodyError?.of(context),
          onChanged: _templateChanged,
        ),
        BusinessCommand(
          label: AppMessage.saveSettings.of(context),
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
              emailSubject: _subject.text == _displayedDefaultSubject
                  ? AppMessage.invoiceEmailDefaultSubject.english
                  : _subject.text,
              emailBody: _body.text == _displayedDefaultBody
                  ? AppMessage.invoiceEmailDefaultBody.english
                  : _body.text,
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
        const SizedBox(height: 24),
        const AutoBackupSettingsSection(),
      ],
    );
  }
}

class CustomersScreen extends ConsumerWidget {
  const CustomersScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: Text(AppMessage.customers.of(context))),
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
      label: Text(AppMessage.customer.of(context)),
    ),
    body: ref
        .watch(businessSettingsProvider)
        .when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: TextButton(
              onPressed: () => ref.invalidate(businessSettingsProvider),
              child: Text(AppMessage.retryCustomers.of(context)),
            ),
          ),
          data: (data) => data.customers.isEmpty
              ? Center(child: Text(AppMessage.customersEmptyHelp.of(context)))
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
                              if (c.archived)
                                AppMessage.customerArchived.of(context),
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
          widget.customer == null
              ? AppMessage.customerNew.of(context)
              : AppMessage.customerDetails.of(context),
        ),
      ),
      bottomNavigationBar: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 12, 24, 12),
          child: BusinessCommand(
            key: ValueKey('save-customer/$_id'),
            label: AppMessage.save.of(context),
            enabled: data != null,
            create: () {
              if (_name.text.trim().isEmpty) {
                throw FormatException(
                  AppMessage.validationCustomerName.of(context),
                );
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
            (_name, AppMessage.customerName.of(context), 1, 240),
            (_email, AppMessage.customerEmailLabel.of(context), 1, 254),
            (_phone, AppMessage.phone.of(context), 1, 120),
            (_address, AppMessage.billingAddress.of(context), 3, 4000),
            (_notes, AppMessage.customerNotes.of(context), 3, 20000),
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
            title: Text(AppMessage.archiveCustomer.of(context)),
            subtitle: Text(AppMessage.archiveCustomerHelp.of(context)),
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
              label: Text(AppMessage.deleteUnusedCustomer.of(context)),
              onPressed: () => showDialog<void>(
                context: context,
                barrierDismissible: false,
                builder: (dialog) => AlertDialog(
                  title: Text(AppMessage.confirmDeleteCustomer.of(dialog)),
                  content: Text(AppMessage.deleteCustomerHelp.of(dialog)),
                  actions: [
                    BusinessCommand(
                      label: AppMessage.deleteCustomer.of(dialog),
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
