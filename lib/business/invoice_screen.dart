import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';

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
  Future<InvoicePage> _preview(int page) async {
    final api = ref.read(reportApiProvider), copy = widget.copyId;
    final result = await (copy == null ? api.preview(widget.invoiceId, page) : api.previewCopy(widget.invoiceId, copy, page));
    if (mounted && result.notice != _notice) setState(() => _notice = result.notice);
    return result;
  }

  late Future<InvoicePage> _page = _preview(0);
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
  String? _message;
  Future<void> _handoff(String kind) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final api = ref.read(reportApiProvider);
      String message;
      if (kind == 'email') {
        if (widget.copyId == null) {
          await api.email(widget.invoiceId);
        } else {
          await api.emailCopy(widget.invoiceId, widget.copyId!);
        }
        message = 'Email composer opened. Review the PDF attachment and press Send in your email app.';
      } else if (kind == 'share') {
        if (widget.copyId == null) {
          await api.share(widget.invoiceId);
        } else {
          await api.shareCopy(widget.invoiceId, widget.copyId!);
        }
        message = 'Share dialog opened.';
      } else {
        message =
            await (widget.copyId == null
                ? api.save(widget.invoiceId)
                : api.saveCopy(widget.invoiceId, widget.copyId!))
            ? 'PDF saved.'
            : 'Save cancelled. Your prepared invoice is kept.';
      }
      if (mounted) {
        setState(() => _message = message);
      }
    } on PlatformException catch (error) {
      if (mounted) {
        setState(
          () => _message = error.code == 'PAYMENT_HISTORY_TOO_LARGE'
              ? 'This history is too long for one PDF. Open individual saved receipts from the invoice.'
              : 'The handoff could not be opened. Try Save PDF or Share. Your saved document is kept.',
        );
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _message = 'The handoff could not be opened. Try Save PDF or Share. Your prepared invoice is kept.',
        );
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
        widget.copyId == null ? 'Prepared invoice' : 'Saved payment copy',
      ),
      actions: [
        IconButton(
          tooltip: 'Payments and balance',
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
                        ? 'This invoice is a saved snapshot. Later job, rate or company changes will not rewrite it.'
                        : 'This copy records the paid amount and balance when it was created. Later payments or corrections do not rewrite it.',
                  ),
                  if (_notice != null) Text(_notice!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  if (_message != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(_message!),
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
                          _notice != null ? 'Email historical copy' : widget.copyId == null ? 'Email invoice' : 'Email copy',
                        ),
                      ),
                      OutlinedButton.icon(
                        onPressed: _busy ? null : () => _handoff('save'),
                        icon: const Icon(Icons.save_alt),
                        label: const Text('Save PDF'),
                      ),
                      IconButton(
                        tooltip: 'Share PDF',
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
                      child: const Text('Retry PDF preview'),
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
                            semanticLabel:
                                'Invoice page ${page.index + 1} of ${page.pages}',
                          ),
                        ),
                      ),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        IconButton(
                          tooltip: 'Previous invoice page',
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
                        Text('Page ${page.index + 1} of ${page.pages}'),
                        IconButton(
                          tooltip: 'Next invoice page',
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
