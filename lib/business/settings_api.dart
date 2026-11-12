import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';

class CompanySettings {
  const CompanySettings({
    required this.currency,
    required this.digits,
    required this.defaultHourlyMinor,
    required this.companyName,
    required this.companyEmail,
    required this.companyPhone,
    required this.companyAddress,
    required this.companyWebsite,
    required this.invoiceHeader,
    required this.invoiceFooter,
    required this.invoiceStyle,
    required this.emailSubject,
    required this.emailBody,
    this.defaultTaxUnits = 0,
    this.receivablesEmail = '',
  });
  final String currency,
      companyName,
      companyEmail,
      companyPhone,
      companyAddress,
      companyWebsite,
      invoiceHeader,
      invoiceFooter,
      invoiceStyle,
      emailSubject,
      emailBody;
  final int digits;
  final int defaultTaxUnits;
  final String receivablesEmail;
  final String? defaultHourlyMinor;
  factory CompanySettings.decode(Map<Object?, Object?> v) => CompanySettings(
    currency: v['currency'] as String,
    digits: v['fractionDigits'] as int,
    defaultHourlyMinor: v['defaultHourlyMinor'] as String?,
    companyName: v['companyName'] as String,
    companyEmail: v['companyEmail'] as String,
    companyPhone: v['companyPhone'] as String,
    companyAddress: v['companyAddress'] as String,
    companyWebsite: v['companyWebsite'] as String,
    invoiceHeader: v['invoiceHeader'] as String,
    invoiceFooter: v['invoiceFooter'] as String,
    invoiceStyle: v['invoiceStyle'] as String,
    emailSubject: v['emailSubject'] as String,
    emailBody: v['emailBody'] as String,
    defaultTaxUnits: v['defaultTaxUnits'] as int? ?? 0,
    receivablesEmail: v['receivablesEmail'] as String? ?? '',
  );
  Map<String, Object?> encode() => {
    'currency': currency,
    'defaultHourlyMinor': defaultHourlyMinor,
    'companyName': companyName,
    'companyEmail': companyEmail,
    'companyPhone': companyPhone,
    'companyAddress': companyAddress,
    'companyWebsite': companyWebsite,
    'invoiceHeader': invoiceHeader,
    'invoiceFooter': invoiceFooter,
    'invoiceStyle': invoiceStyle,
    'emailSubject': emailSubject,
    'emailBody': emailBody,
    'defaultTaxUnits': defaultTaxUnits,
    'receivablesEmail': receivablesEmail,
  };
}

class Customer {
  const Customer({
    required this.id,
    required this.name,
    this.email = '',
    this.phone = '',
    this.address = '',
    this.notes = '',
    this.archived = false,
  });
  final String id, name, email, phone, address, notes;
  final bool archived;
  factory Customer.decode(Map<Object?, Object?> v) => Customer(
    id: v['customerId'] as String,
    name: v['name'] as String,
    email: v['email'] as String,
    phone: v['phone'] as String,
    address: v['address'] as String,
    notes: v['notes'] as String,
    archived: v['archived'] as bool,
  );
  Map<String, Object?> encode() => {
    'customerId': id,
    'name': name,
    'email': email,
    'phone': phone,
    'address': address,
    'notes': notes,
    'archived': archived,
  };
}

class BusinessSettings {
  const BusinessSettings(this.version, this.settings, this.customers);
  final int version;
  final CompanySettings settings;
  final List<Customer> customers;
}

class JobPreferences {
  const JobPreferences({
    required this.version,
    required this.id,
    required this.name,
    required this.color,
    required this.mode,
    required this.currency,
    required this.digits,
    required this.archived,
    required this.effectiveEmail,
    this.archivedMillis,
    this.canDelete = false,
    this.rate,
    this.effectiveRate,
    this.customerId,
    this.emailOverride,
    this.taxUnits,
    this.effectiveTaxUnits = 0,
  });
  final int version, color, digits;
  final int? archivedMillis;
  final int? taxUnits;
  final int effectiveTaxUnits;
  final String id, name, mode, currency, effectiveEmail;
  final String? rate, effectiveRate, customerId, emailOverride;
  final bool archived;
  final bool canDelete;
  factory JobPreferences.decode(Map<Object?, Object?> v) {
    final s = v['settings'] as Map<Object?, Object?>;
    return JobPreferences(
      version: v['version'] as int,
      id: s['jobId'] as String,
      name: s['name'] as String,
      color: s['colorArgb'] as int,
      mode: s['billingMode'] as String,
      currency: s['currency'] as String,
      digits: s['fractionDigits'] as int,
      archived: v['archived'] as bool,
      canDelete: v['canDelete'] as bool? ?? false,
      effectiveEmail: v['effectiveEmail'] as String,
      archivedMillis: v['archivedMillis'] as int?,
      rate: s['hourlyRateMinor'] as String?,
      effectiveRate: v['effectiveHourlyMinor'] as String?,
      customerId: s['customerId'] as String?,
      emailOverride: s['customerEmailOverride'] as String?,
      taxUnits: s['taxUnits'] as int?,
      effectiveTaxUnits: v['effectiveTaxUnits'] as int? ?? 0,
    );
  }
  Map<String, Object?> encode() => {
    'jobId': id,
    'name': name,
    'colorArgb': color,
    'billingMode': mode,
    'currency': currency,
    'hourlyRateMinor': rate,
    'customerId': customerId,
    'customerEmailOverride': emailOverride,
    'taxUnits': taxUnits,
  };
}

class BusinessSettingsApi {
  const BusinessSettingsApi();
  static const _channel = MethodChannel('clockrove/business/v1');
  Future<BusinessSettings> read() async {
    final v = (await _channel.invokeMapMethod<Object?, Object?>('business'))!;
    return BusinessSettings(
      v['version'] as int,
      CompanySettings.decode(v['settings'] as Map<Object?, Object?>),
      (v['customers'] as List)
          .map((c) => Customer.decode(c as Map<Object?, Object?>))
          .toList(),
    );
  }

  Future<JobPreferences> job(String id) async => JobPreferences.decode(
    (await _channel.invokeMapMethod<Object?, Object?>('job', {'jobId': id}))!,
  );
  Future<TimerOutcome> _save(
    String method,
    String id,
    int version,
    Map<String, Object?> value,
  ) async {
    final r = (await _channel.invokeMapMethod<String, Object?>(method, {
      'id': id,
      'version': version,
      'value': value,
    }))!;
    return TimerOutcome(r['version'] as int, r['conflict'] as String?);
  }

  Future<TimerOutcome> settings(String id, int version, CompanySettings s) =>
      _save('saveSettings', id, version, s.encode());
  Future<TimerOutcome> customer(String id, int version, Customer c) =>
      _save('saveCustomer', id, version, c.encode());
  Future<TimerOutcome> removeCustomer(
    String id,
    int version,
    String customerId,
  ) => _save('removeCustomer', id, version, {'customerId': customerId});
  Future<TimerOutcome> saveJob(String id, JobPreferences p) =>
      _save('saveJob', id, p.version, p.encode());
}

final businessSettingsApiProvider = Provider<BusinessSettingsApi>(
  (ref) => const BusinessSettingsApi(),
);
final businessSettingsProvider = FutureProvider<BusinessSettings>((ref) {
  ref.watch(timerVersionsProvider);
  return ref.watch(businessSettingsApiProvider).read();
});
final jobPreferencesProvider = FutureProvider.family<JobPreferences, String>((
  ref,
  id,
) {
  ref.watch(timerVersionsProvider);
  return ref.watch(businessSettingsApiProvider).job(id);
});

String? accountingEmailError(String input) {
  final value = input.trim();
  if (value.isEmpty) return null;
  if (value.length > 254 ||
      value.codeUnits.any((c) => c <= 32 || c == 127) ||
      !RegExp(r'^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$').hasMatch(value)) {
    return 'Enter one email address, or leave blank to use the company email.';
  }
  return null;
}
