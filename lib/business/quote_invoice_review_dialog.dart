import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'quote_invoice_api.dart';
import 'quote_invoice_format.dart';
import 'tax_rate.dart';

final class QuoteInvoiceReviewDialog extends StatelessWidget {
  const QuoteInvoiceReviewDialog({
    super.key,
    required this.request,
    required this.choices,
    required this.data,
    required this.result,
  });

  final QuoteInvoiceRequest request;
  final QuoteInvoiceChoices choices;
  final QuoteInvoiceContext data;
  final QuoteInvoiceCalculation result;

  @override
  Widget build(BuildContext context) {
    final accepted = data.issued!;
    return AlertDialog(
      title: Text(AppMessage.prepareInvoiceQuestion.of(context)),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              AppMessage.quoteInvoiceRevisionRef.format(context, {
                'number': accepted.number,
                'revision': accepted.revision.toString(),
              }),
            ),
            Text(data.jobName),
            Text(
              AppMessage.quoteInvoiceDateRange.format(context, {
                'first': request.firstDate,
                'last': request.lastDate,
                'timezone': request.timezone,
              }),
            ),
            Text(
              AppMessage.quoteInvoiceReviewCustomer.format(context, {
                'customer': result.customer.isEmpty
                    ? AppMessage.quoteInvoiceCustomerNotSpecified.of(context)
                    : result.customer,
              }),
            ),
            Text(
              AppMessage.quoteInvoiceReviewEmail.format(context, {
                'email': result.email.isEmpty
                    ? AppMessage.quoteInvoiceEmailNotSet.of(context)
                    : result.email,
              }),
            ),
            Text(
              AppMessage.quoteInvoiceReviewPricesLabel.format(context, {
                'basis':
                    (choices.currentPrices
                            ? AppMessage.quoteInvoiceReviewCurrentPrices
                            : AppMessage.quoteInvoiceReviewAcceptedAgreement)
                        .of(context),
              }),
            ),
            Text(
              AppMessage.quoteInvoiceReviewTax.format(context, {
                'rate': taxPercent(result.taxUnits!),
                'basis':
                    (choices.currentTax
                            ? AppMessage.quoteInvoiceReviewCurrentJobTax
                            : AppMessage.quoteInvoiceReviewAcceptedTaxBasis)
                        .of(context),
              }),
            ),
            for (final task in data.tasks.where(
              (task) => choices.taskIds.contains(task.id),
            ))
              Text(
                AppMessage.quoteInvoiceReviewTaskLine.format(context, {
                  'title': task.title,
                  'quantity': taskQuantity(task.actualQuantity),
                  'currency': data.currency,
                  'amount': money(
                    choices.currentPrices
                        ? task.currentPrice
                        : task.acceptedPrice,
                    data.digits,
                  ),
                }),
              ),
            for (final time in choices.times)
              Text(
                AppMessage.quoteInvoiceReviewTimeLine.format(context, {
                  'title': time.label,
                  'duration': quoteInvoiceDurationLabel(
                    context,
                    time.durationMillis,
                  ),
                }),
              ),
            const SizedBox(height: 12),
            Text(
              AppMessage.invoiceAmountLine.format(context, {
                'label': AppMessage.invoiceSubtotal.of(context),
                'amount':
                    '${data.currency} ${money(result.subtotal, data.digits)}',
              }),
            ),
            Text(
              AppMessage.invoiceAmountLine.format(context, {
                'label': AppMessage.invoiceTax.of(context),
                'amount':
                    '${data.currency} ${money(result.tax, data.digits)}',
              }),
            ),
            Text(
              AppMessage.invoiceAmountLine.format(context, {
                'label': AppMessage.invoiceTotal.of(context),
                'amount':
                    '${data.currency} ${money(result.total, data.digits)}',
              }),
              style: Theme.of(context).textTheme.titleLarge,
            ),
            Text(
              AppMessage.quoteInvoiceWholeEstimate.format(context, {
                'currency': data.currency,
                'total': accepted.total,
              }),
            ),
            const SizedBox(height: 12),
            if (choices.note.isNotEmpty)
              Text(
                AppMessage.quoteInvoicePrivateReviewNote.format(context, {
                  'note': choices.note,
                }),
              ),
            Text(AppMessage.quoteInvoiceAllocationNotice.of(context)),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: Text(AppMessage.keepEditing.of(context)),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: Text(AppMessage.confirmQuoteInvoiceReview.of(context)),
        ),
      ],
    );
  }
}
