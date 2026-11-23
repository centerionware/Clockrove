import 'package:flutter/widgets.dart';

import '../l10n/app_localizations.dart';

String actualCostCategoryLabel(BuildContext context, String category) =>
    switch (category) {
      'MATERIALS' => AppMessage.actualCostMaterials.of(context),
      'SUBCONTRACT_LABOR' => AppMessage.actualCostSubcontractLabor.of(context),
      'OTHER_DIRECT' => AppMessage.actualCostOtherDirect.of(context),
      'OVERHEAD' => AppMessage.actualCostOverhead.of(context),
      _ => AppMessage.actualCostUnknownCategory.of(context),
    };
