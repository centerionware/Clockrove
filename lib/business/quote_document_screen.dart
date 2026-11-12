import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'quote_api.dart';
import 'report_api.dart';

class QuoteDocumentScreen extends ConsumerStatefulWidget {
  const QuoteDocumentScreen({
    super.key,
    required this.quoteId,
    required this.revision,
    required this.number,
  });
  final String quoteId, number;
  final int revision;
  @override
  ConsumerState<QuoteDocumentScreen> createState() =>
      _QuoteDocumentScreenState();
}

class _QuoteDocumentScreenState extends ConsumerState<QuoteDocumentScreen> {
  late Future<InvoicePage> _page = _load(0);
  bool _busy = false;
  Future<InvoicePage> _load(int page) =>
      ref.read(quoteApiProvider).preview(widget.quoteId, widget.revision, page);
  void _feedback(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _action(String action) async {
    if (_busy) {
      return;
    }
    setState(() => _busy = true);
    final api = ref.read(quoteApiProvider);
    try {
      if (action == 'email') {
        await api.email(widget.quoteId, widget.revision);
        if (mounted) {
          _feedback('Email draft opened. Review the quote and press Send in your email app.');
        }
      } else if (action == 'share') {
        await api.share(widget.quoteId, widget.revision);
      } else {
        final saved = await api.savePdf(widget.quoteId, widget.revision);
        if (mounted) {
          _feedback(saved ? 'Quote PDF saved.' : 'Save cancelled. Your issued quote is retained.');
        }
      }
    } on PlatformException catch (error) {
      if (mounted) {
        _feedback(error.message ?? 'The document could not be opened. Try saving the PDF.');
      }
    } catch (_) {
      if (mounted) {
        _feedback('The quote could not be shared. Its issued revision is retained.');
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
      title: Text('${widget.number} · Revision ${widget.revision}'),
      actions: [
        IconButton(
          tooltip: 'Refresh quote PDF',
          onPressed: _busy ? null : () => setState(() => _page = _load(0)),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: SafeArea(
      child: FutureBuilder<InvoicePage>(
        future: _page,
        builder: (context, snapshot) {
          if (snapshot.hasError) {
            return Center(
              child: TextButton(
                onPressed: () => setState(() => _page = _load(0)),
                child: const Text('Retry quote PDF'),
              ),
            );
          }
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final page = snapshot.data!;
          final loading = snapshot.connectionState == ConnectionState.waiting;
          return Column(
            children: [
              Flexible(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Quote · estimate only',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const Text(
                        'This issued revision is retained. Opening an email draft does not record acceptance or payment.',
                      ),
                      if (page.notice != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: Text(
                            page.notice!,
                            style: TextStyle(
                              color: Theme.of(context).colorScheme.error,
                            ),
                          ),
                        ),
                      const SizedBox(height: 12),
                      Wrap(
                        spacing: 12,
                        runSpacing: 8,
                        children: [
                          FilledButton.icon(
                            onPressed: _busy ? null : () => _action('email'),
                            icon: const Icon(Icons.mail_outline),
                            label: const Text('Email quote'),
                          ),
                          OutlinedButton.icon(
                            onPressed: _busy ? null : () => _action('save'),
                            icon: const Icon(Icons.download),
                            label: const Text('Save PDF'),
                          ),
                          IconButton(
                            tooltip: 'Share quote PDF',
                            onPressed: _busy ? null : () => _action('share'),
                            icon: const Icon(Icons.share_outlined),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              if (_busy || loading) const LinearProgressIndicator(),
              Expanded(
                flex: 2,
                child: InteractiveViewer(
                  minScale: .5,
                  maxScale: 5,
                  child: Center(
                    child: Image.memory(
                      page.png,
                      fit: BoxFit.contain,
                      semanticLabel:
                          'Quote page ${page.index + 1} of ${page.pages}',
                    ),
                  ),
                ),
              ),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    tooltip: 'Previous quote page',
                    onPressed: loading || page.index == 0
                        ? null
                        : () => setState(() => _page = _load(page.index - 1)),
                    icon: const Icon(Icons.chevron_left),
                  ),
                  Text('Page ${page.index + 1} of ${page.pages}'),
                  IconButton(
                    tooltip: 'Next quote page',
                    onPressed: loading || page.index + 1 >= page.pages
                        ? null
                        : () => setState(() => _page = _load(page.index + 1)),
                    icon: const Icon(Icons.chevron_right),
                  ),
                ],
              ),
            ],
          );
        },
      ),
    ),
  );
}
