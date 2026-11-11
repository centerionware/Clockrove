import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'settings_api.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});
  @override Widget build(BuildContext context, WidgetRef ref) => Scaffold(appBar: AppBar(title: const Text('Settings')), body: ref.watch(businessSettingsProvider).when(loading: () => const Center(child: CircularProgressIndicator()), error: (_, _) => Center(child: FilledButton(onPressed: () => ref.invalidate(businessSettingsProvider), child: const Text('Retry settings'))), data: (data) => _CompanyForm(initial: data.settings)));
}
class _CompanyForm extends ConsumerStatefulWidget {
  const _CompanyForm({required this.initial});
  final CompanySettings initial;
  @override ConsumerState<_CompanyForm> createState() => _CompanyFormState();
}
class _CompanyFormState extends ConsumerState<_CompanyForm> {
  late final _rate = TextEditingController(text: money(widget.initial.defaultHourlyMinor, widget.initial.digits));
  late final _name = TextEditingController(text: widget.initial.companyName);
  late final _email = TextEditingController(text: widget.initial.companyEmail);
  late final _phone = TextEditingController(text: widget.initial.companyPhone);
  late final _address = TextEditingController(text: widget.initial.companyAddress);
  late final _website = TextEditingController(text: widget.initial.companyWebsite);
  late final _header = TextEditingController(text: widget.initial.invoiceHeader);
  late final _footer = TextEditingController(text: widget.initial.invoiceFooter);
  late final _subject = TextEditingController(text: widget.initial.emailSubject);
  late final _body = TextEditingController(text: widget.initial.emailBody);
  late String _currency = widget.initial.currency, _style = widget.initial.invoiceStyle;
  late int _digits = widget.initial.digits;
  @override void dispose() { for (final c in [_rate, _name, _email, _phone, _address, _website, _header, _footer, _subject, _body]) { c.dispose(); } super.dispose(); }
  Widget _field(TextEditingController c, String label, {int lines = 1, int length = 4000}) => Padding(padding: const EdgeInsets.only(bottom: 16), child: TextField(controller: c, decoration: InputDecoration(labelText: label), minLines: lines, maxLines: lines == 1 ? 1 : lines + 4, maxLength: length));
  @override Widget build(BuildContext context) {
    final current = ref.watch(businessSettingsProvider).asData?.value;
    final currencies = <String, int>{'USD': 2, 'EUR': 2, 'GBP': 2, 'CAD': 2, 'AUD': 2, 'NZD': 2, 'CHF': 2, 'JPY': 0, 'INR': 2, 'ZAR': 2, 'SEK': 2, 'NOK': 2, 'DKK': 2, 'KWD': 3, widget.initial.currency: widget.initial.digits};
    return ListView(padding: const EdgeInsets.all(24), children: [
      Text('Billing defaults', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 16),
      DropdownButtonFormField<String>(initialValue: _currency, decoration: const InputDecoration(labelText: 'Currency for new jobs'), items: [for (final c in currencies.keys) DropdownMenuItem(value: c, child: Text(c))], onChanged: (v) { if (v != null && v != _currency) setState(() { _currency = v; _digits = currencies[v]!; _rate.clear(); }); }),
      const Padding(padding: EdgeInsets.symmetric(vertical: 12), child: Text('Existing jobs keep their currency. Changing currency clears this default rate; enter a new amount explicitly.')),
      TextField(controller: _rate, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: InputDecoration(labelText: 'Default hourly rate ($_currency)', helperText: 'Blank means no rate set. Override this in any job.')),
      const SizedBox(height: 32), Text('Company & contact', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 16),
      _field(_name, 'Company name', length: 240), _field(_email, 'Company email', length: 254), _field(_phone, 'Phone', length: 120), _field(_address, 'Address', lines: 2), _field(_website, 'Website', length: 2000),
      const SizedBox(height: 16), Text('Invoice appearance', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 16),
      DropdownButtonFormField<String>(initialValue: _style, decoration: const InputDecoration(labelText: 'Invoice style'), items: const [DropdownMenuItem(value: 'MINIMAL', child: Text('Minimal')), DropdownMenuItem(value: 'CLASSIC', child: Text('Classic')), DropdownMenuItem(value: 'MODERN', child: Text('Modern'))], onChanged: (v) { if (v != null) setState(() => _style = v); }),
      const SizedBox(height: 20), _field(_header, 'Optional invoice header', lines: 2), _field(_footer, 'Optional invoice footer', lines: 2),
      Text('Email draft', style: Theme.of(context).textTheme.titleLarge), const SizedBox(height: 16),
      const Text('Placeholders: {company_name}, {customer_name}, {job_name}, {invoice_number}, {amount}, {currency}. The email app lets you review the attached invoice before sending.'), const SizedBox(height: 16),
      _field(_subject, 'Email subject template', length: 500), _field(_body, 'Email body template', lines: 5, length: 20000),
      BusinessCommand(label: 'Save settings', enabled: current != null, create: () { final id = newBusinessId(); final version = current!.version; final s = CompanySettings(currency: _currency, digits: _digits, defaultHourlyMinor: parseMoney(_rate.text, _digits), companyName: _name.text.trim(), companyEmail: _email.text.trim(), companyPhone: _phone.text, companyAddress: _address.text, companyWebsite: _website.text, invoiceHeader: _header.text, invoiceFooter: _footer.text, invoiceStyle: _style, emailSubject: _subject.text, emailBody: _body.text); return () => ref.read(businessSettingsApiProvider).settings(id, version, s); }, onCommitted: () { ref.invalidate(businessSettingsProvider); Navigator.pop(context); }),
    ]);
  }
}

class CustomersScreen extends ConsumerWidget {
  const CustomersScreen({super.key});
  @override Widget build(BuildContext context, WidgetRef ref) => Scaffold(appBar: AppBar(title: const Text('Customers')), floatingActionButton: FloatingActionButton.extended(onPressed: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => const CustomerEditor())), icon: const Icon(Icons.person_add_alt), label: const Text('Customer')), body: ref.watch(businessSettingsProvider).when(loading: () => const Center(child: CircularProgressIndicator()), error: (_, _) => Center(child: TextButton(onPressed: () => ref.invalidate(businessSettingsProvider), child: const Text('Retry customers'))), data: (data) => data.customers.isEmpty ? const Center(child: Text('Add a customer to keep billing details with your jobs.')) : ListView(padding: const EdgeInsets.fromLTRB(20, 12, 20, 100), children: [for (final c in data.customers) Card(child: ListTile(leading: CircleAvatar(child: Icon(c.archived ? Icons.archive_outlined : Icons.person_outline)), title: Text(c.name), subtitle: Text([if (c.archived) 'Archived', if (c.email.isNotEmpty) c.email, if (c.phone.isNotEmpty) c.phone].join(' · ')), trailing: const Icon(Icons.chevron_right), onTap: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => CustomerEditor(customer: c)))))]))));
}
class CustomerEditor extends ConsumerStatefulWidget {
  const CustomerEditor({super.key, this.customer});
  final Customer? customer;
  @override ConsumerState<CustomerEditor> createState() => _CustomerEditorState();
}
class _CustomerEditorState extends ConsumerState<CustomerEditor> {
  late final _id = widget.customer?.id ?? newBusinessId();
  late final _name = TextEditingController(text: widget.customer?.name ?? '');
  late final _email = TextEditingController(text: widget.customer?.email ?? '');
  late final _phone = TextEditingController(text: widget.customer?.phone ?? '');
  late final _address = TextEditingController(text: widget.customer?.address ?? '');
  late final _notes = TextEditingController(text: widget.customer?.notes ?? '');
  late bool _archived = widget.customer?.archived ?? false;
  @override void dispose() { for (final c in [_name, _email, _phone, _address, _notes]) { c.dispose(); } super.dispose(); }
  @override Widget build(BuildContext context) {
    final data = ref.watch(businessSettingsProvider).asData?.value;
    return Scaffold(appBar: AppBar(title: Text(widget.customer == null ? 'New customer' : 'Customer details')), body: ListView(padding: const EdgeInsets.all(24), children: [
      for (final (controller, label, lines, length) in [(_name, 'Customer name', 1, 240), (_email, 'Email', 1, 254), (_phone, 'Phone', 1, 120), (_address, 'Billing address', 3, 4000), (_notes, 'Notes', 3, 20000)]) Padding(padding: const EdgeInsets.only(bottom: 16), child: TextField(controller: controller, decoration: InputDecoration(labelText: label), minLines: lines, maxLines: lines + 2, maxLength: length)),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('Archive customer'), subtitle: const Text('Keep existing job contacts; hide from new assignments.'), value: _archived, onChanged: (v) => setState(() => _archived = v)),
      const SizedBox(height: 24), BusinessCommand(label: 'Save customer', enabled: data != null, create: () { if (_name.text.trim().isEmpty) { throw const FormatException('Enter a customer name.'); } final id = newBusinessId(); final version = data!.version; final c = Customer(id: _id, name: _name.text.trim(), email: _email.text.trim(), phone: _phone.text, address: _address.text, notes: _notes.text, archived: _archived); return () => ref.read(businessSettingsApiProvider).customer(id, version, c); }, onCommitted: () { ref.invalidate(businessSettingsProvider); Navigator.pop(context); }),
      if (widget.customer != null) ...[const SizedBox(height: 32), OutlinedButton.icon(style: OutlinedButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error), icon: const Icon(Icons.delete_outline), label: const Text('Delete unused customer'), onPressed: () => showDialog<void>(context: context, barrierDismissible: false, builder: (dialog) => AlertDialog(title: const Text('Delete customer?'), content: const Text('Only unused customers can be deleted. Archive a customer assigned to jobs or invoices.'), actions: [BusinessCommand(label: 'Delete customer', enabled: data != null, create: () { final id = newBusinessId(); final version = data!.version; return () => ref.read(businessSettingsApiProvider).removeCustomer(id, version, _id); }, onCommitted: () { ref.invalidate(businessSettingsProvider); Navigator.pop(dialog); Navigator.pop(context); })])))],
    ]));
  }
}
