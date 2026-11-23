import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'report_api.dart';

class InvoiceStylePreviewScreen extends ConsumerStatefulWidget {
  const InvoiceStylePreviewScreen({
    super.key,
    required this.style,
    required this.currency,
    required this.digits,
    required this.logo,
  });

  final String style, currency;
  final int digits;
  final Uint8List? logo;

  @override
  ConsumerState<InvoiceStylePreviewScreen> createState() =>
      _InvoiceStylePreviewScreenState();
}

class _InvoiceStylePreviewScreenState
    extends ConsumerState<InvoiceStylePreviewScreen> {
  late Future<InvoicePage> _page = _load(0);

  Future<InvoicePage> _load(int index) => ref
      .read(reportApiProvider)
      .previewStyle(
        style: widget.style,
        currency: widget.currency,
        digits: widget.digits,
        logo: widget.logo,
        page: index,
      );

  String _styleName(BuildContext context) => switch (widget.style) {
    'CLASSIC' => AppMessage.invoiceStyleClassic.of(context),
    'MODERN' => AppMessage.invoiceStyleModern.of(context),
    _ => AppMessage.invoiceStyleMinimal.of(context),
  };

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(AppMessage.invoiceStylePreviewTitle.of(context))),
    body: SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(16),
              ),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    const Icon(Icons.visibility_outlined),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            AppMessage.invoiceStyleSampleTitle.format(context, {
                              'style': _styleName(context),
                            }),
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const SizedBox(height: 4),
                          Text(AppMessage.invoiceStylePreviewDisclaimer.of(context)),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          Expanded(
            child: FutureBuilder<InvoicePage>(
              future: _page,
              builder: (context, state) {
                if (state.hasError) {
                  return Center(
                    child: TextButton.icon(
                      onPressed: () => setState(() => _page = _load(0)),
                      icon: const Icon(Icons.refresh),
                      label: Text(AppMessage.invoiceReviewRetry.of(context)),
                    ),
                  );
                }
                if (!state.hasData) {
                  return const Center(child: CircularProgressIndicator());
                }
                final page = state.data!;
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
                            semanticLabel: AppMessage.documentPage.format(context, {
                              'page': MaterialLocalizations.of(context)
                                  .formatDecimal(page.index + 1),
                              'total': MaterialLocalizations.of(context)
                                  .formatDecimal(page.pages),
                            }),
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
                              state.connectionState ==
                                      ConnectionState.waiting ||
                                  page.index == 0
                              ? null
                              : () => setState(
                                  () => _page = _load(page.index - 1),
                                ),
                          icon: const Icon(Icons.chevron_left),
                        ),
                        Text(
                          AppMessage.documentPage.format(context, {
                            'page': MaterialLocalizations.of(context)
                                .formatDecimal(page.index + 1),
                            'total': MaterialLocalizations.of(context)
                                .formatDecimal(page.pages),
                          }),
                        ),
                        IconButton(
                          tooltip: AppMessage.invoiceNextPage.of(context),
                          onPressed:
                              state.connectionState ==
                                      ConnectionState.waiting ||
                                  page.index + 1 >= page.pages
                              ? null
                              : () => setState(
                                  () => _page = _load(page.index + 1),
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
