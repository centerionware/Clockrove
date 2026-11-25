import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';

import '../l10n/app_localizations.dart';
import 'report_api.dart';
import 'invoice_detail_screen.dart';

class InvoiceScreen extends ConsumerStatefulWidget {
  const InvoiceScreen({
    super.key,
    required this.invoiceId,
    this.copyId,
    this.composeOnOpen = false,
  });
  final String invoiceId;
  final String? copyId;
  final bool composeOnOpen;
  @override
  ConsumerState<InvoiceScreen> createState() => _InvoiceScreenState();
}

class _InvoiceScreenState extends ConsumerState<InvoiceScreen> {
  String? _notice;
  String? _languageTag;
  Future<InvoicePage> _preview(int page) async {
    final api = ref.read(reportApiProvider), copy = widget.copyId;
    final languageTag = Localizations.localeOf(context).toLanguageTag();
    final result = await (copy == null
        ? api.preview(widget.invoiceId, page, languageTag: languageTag)
        : api.previewCopy(
            widget.invoiceId,
            copy,
            page,
            languageTag: languageTag,
          ));
    if (mounted && result.notice != _notice) {
      setState(() => _notice = result.notice);
    }
    return result;
  }

  late Future<InvoicePage> _page = _preview(0);
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final tag = Localizations.localeOf(context).toLanguageTag();
    if (_languageTag != null && _languageTag != tag) {
      _page = _preview(0);
      _notice = null;
    }
    _languageTag = tag;
  }

  @override
  void initState() {
    super.initState();
    if (widget.composeOnOpen) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _handoff('email');
      });
    }
  }

  bool _busy = false;
  AppMessage? _message;
  Future<void> _handoff(String kind) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final api = ref.read(reportApiProvider);
      final languageTag = Localizations.localeOf(context).toLanguageTag();
      final copy = widget.copyId;
      AppMessage message;
      if (kind == 'email') {
        if (copy == null) {
          await api.email(widget.invoiceId, languageTag: languageTag);
        } else {
          await api.emailCopy(
            widget.invoiceId,
            copy,
            languageTag: languageTag,
          );
        }
        message = AppMessage.invoiceEmailOpened;
      } else if (kind == 'share') {
        if (copy == null) {
          await api.share(widget.invoiceId, languageTag: languageTag);
        } else {
          await api.shareCopy(
            widget.invoiceId,
            copy,
            languageTag: languageTag,
          );
        }
        message = AppMessage.documentShareOpened;
      } else {
        message =
            await (copy == null
                ? api.save(widget.invoiceId, languageTag: languageTag)
                : api.saveCopy(
                    widget.invoiceId,
                    copy,
                    languageTag: languageTag,
                  ))
            ? AppMessage.reportPdfSaved
            : AppMessage.invoiceSaveCancelled;
      }
      if (mounted) {
        setState(() => _message = message);
      }
    } on PlatformException catch (error) {
      if (mounted) {
        setState(
          () => _message = error.code == 'PAYMENT_HISTORY_TOO_LARGE'
              ? AppMessage.paymentHistoryPdfTooLarge
              : AppMessage.invoiceHandoffFailed,
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() => _message = AppMessage.invoicePreparedHandoffFailed);
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(
        widget.copyId == null
            ? AppMessage.invoicePrepared.of(context)
            : widget.copyId!.startsWith('void-')
            ? AppMessage.invoiceCancellationCopy.of(context)
            : AppMessage.invoicePaymentCopy.of(context),
      ),
      actions: [
        IconButton(
          tooltip: AppMessage.invoicePaymentsBalance.of(context),
          onPressed: _busy
              ? null
              : () => Navigator.push<void>(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) =>
                        InvoiceDetailScreen(invoiceId: widget.invoiceId),
                  ),
                ),
          icon: const Icon(Icons.payments_outlined),
        ),
      ],
    ),
    body: SafeArea(
      child: Column(
        children: [
          ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * .42,
            ),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    widget.copyId == null
                        ? AppMessage.invoiceScreenSnapshot.of(context)
                        : widget.copyId!.startsWith('void-')
                        ? AppMessage.invoiceScreenCancellationCopy.of(context)
                        : AppMessage.invoiceScreenPaymentCopy.of(context),
                  ),
                  if (_notice != null)
                    Text(
                      _notice!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  if (_message != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(_message!.of(context)),
                    ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 12,
                    runSpacing: 8,
                    children: [
                      FilledButton.icon(
                        onPressed: _busy ? null : () => _handoff('email'),
                        icon: const Icon(Icons.email_outlined),
                        label: Text(
                          _notice != null
                              ? AppMessage.invoiceEmailHistorical.of(context)
                              : widget.copyId == null
                              ? AppMessage.invoiceEmail.of(context)
                              : AppMessage.invoiceEmailCopy.of(context),
                        ),
                      ),
                      OutlinedButton.icon(
                        onPressed: _busy ? null : () => _handoff('save'),
                        icon: const Icon(Icons.save_alt),
                        label: Text(AppMessage.documentSavePdf.of(context)),
                      ),
                      IconButton(
                        tooltip: AppMessage.documentSharePdf.of(context),
                        onPressed: _busy ? null : () => _handoff('share'),
                        icon: const Icon(Icons.share_outlined),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          Expanded(
            child: FutureBuilder<InvoicePage>(
              future: _page,
              builder: (context, snapshot) {
                if (snapshot.hasError) {
                  return Center(
                    child: TextButton(
                      onPressed: () => setState(() => _page = _preview(0)),
                      child: Text(AppMessage.invoiceScreenRetryPdf.of(context)),
                    ),
                  );
                }
                if (!snapshot.hasData) {
                  return const Center(child: CircularProgressIndicator());
                }
                final page = snapshot.data!;
                return Column(
                  children: [
                    Expanded(
                      child: InteractiveViewer(
                        minScale: .5,
                        maxScale: 5,
                        child: Center(
                          child: Image.memory(
                            page.png,
                            fit: BoxFit.contain,
                            semanticLabel: AppMessage.documentPage.format(
                              context,
                              {
                                'page': '${page.index + 1}',
                                'total': '${page.pages}',
                              },
                            ),
                          ),
                        ),
                      ),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        IconButton(
                          tooltip: AppMessage.invoicePreviousPage.of(context),
                          onPressed:
                              snapshot.connectionState ==
                                      ConnectionState.waiting ||
                                  page.index == 0
                              ? null
                              : () => setState(
                                  () => _page = _preview(page.index - 1),
                                ),
                          icon: const Icon(Icons.chevron_left),
                        ),
                        Text(
                          AppMessage.documentPage.format(context, {
                            'page': '${page.index + 1}',
                            'total': '${page.pages}',
                          }),
                        ),
                        IconButton(
                          tooltip: AppMessage.invoiceNextPage.of(context),
                          onPressed:
                              snapshot.connectionState ==
                                      ConnectionState.waiting ||
                                  page.index + 1 >= page.pages
                              ? null
                              : () => setState(
                                  () => _page = _preview(page.index + 1),
                                ),
                          icon: const Icon(Icons.chevron_right),
                        ),
                      ],
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    ),
  );
}
