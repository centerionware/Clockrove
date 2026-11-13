# Clockrove UI direction

The owner requires a clean, modern interface from the prototype onward. The
reference implementation is `ClockroveTheme` and the timer screen, validated in
Actions run 42996201315 and visually reviewed in both light and dark previews.
It is not a full launch acceptance pass or physical-device result.

The owner's usability bar is that the interface should be “so intuitive a
trained monkey could use it.” Common first-use tasks should need no training or
guessing: use plain labels, obvious next actions, consistent controls, and
actionable explanations for errors and permission states. Expose advanced choices
when relevant, and check each feature's main path from a first-time user's point
of view. After all launch features are implemented, the whole-app UI overhaul and
exhaustive actual-app E2E/screenshot gate runs before closed testing. It covers
every feature, screen and selectable option, with first-use journeys, native
persistence checks and reviewed screenshots on API 24 and current stable Android.
Public user documentation is a later Cloudflare Pages deliverable.

Use the forest-green/neutral light palette and mint/deep-green dark palette in
app_theme.dart. Keep one strong primary action per context, clear typography,
tabular elapsed digits, generous spacing and quiet grouped surfaces. Content
stays within a readable width; long screens scroll. Interactive targets remain
at least 48 logical pixels and text must remain usable at large scaling.

The session card owns the strongest hierarchy, followed by jobs and contextual
location health. New job stays last. Prefer real capability/status information
over decorative dashboards. Show only implemented actions; do not add placeholder
navigation, fake totals, simulated tracking or disabled previews of future paid
features. Elapsed time is derived from native timestamps, never accumulated in UI.

Location UI distinguishes permission, service request, actual measured samples
and provider failure. It never equates requesting updates with guaranteed GPS
delivery. Manual timing remains available when location permission is denied.
Future boards/reports/backup screens should reuse the same palette, spacing,
control hierarchy and explicit error states. Avoid unprompted technical details
in product copy.

Home-screen widgets use a transparent outer surface with a visible, restrained
border instead of an opaque card background. Preserve readable foreground
content and usable controls across light and dark launcher wallpapers. Review
Actions-rendered captures of Timer, Tasks and Combined at compact and expanded
sizes against contrasting wallpapers before release.

Invoice and quote PDFs put customer billing details on the left and company
details on the right, with each column limited to 47% of printable width. Long
names and contact/address text wrap within their column and paginate without
clipping. Keep subtotal/tax rows flush right; use regular weight for labels and
tax rates and bold only for the numeric subtotal/tax amounts. Review generated
PDF pages for all three document styles in Actions.
Place a **Preview invoice** action immediately below the style selector in main
Settings. It opens a paginated preview from the real native renderer using the
current selection, saved logo if present and clearly identified synthetic data;
it does not create or send an invoice.

Synthetic light/dark previews are rendered in GitHub Actions, not on the VM.
Review actual images and interaction/accessibility test results before merging
visual changes. The first preview's debug ribbon is absent from release builds;
the observation-status follow-up explicitly hides it in debug/preview wrappers too.

Android launcher resources use the same forest background and simple clock mark:
legacy vector on API 24–25, adaptive foreground/background on API 26+, and a
monochrome themed layer on API 33+. Launch surfaces follow the light/dark app
background, including Android 12's system splash. These are source vector assets;
no raster generation tool or new runtime dependency is required. Compilation and
CI-rendered resource previews must pass before merging. Actual launcher masking
and launch transitions still require physical-device review.

Platform references: [adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive),
[system splash screen](https://developer.android.com/develop/ui/views/launch/splash-screen).

Native previews from Actions run 43001359856 / artifact 13845869738 were inspected:
legacy/adaptive icons, monochrome mark and both launch backgrounds are clean and
consistent. This validates rendered resources under Robolectric, not actual device
launcher masks or system launch animations. Combined-main validation is separate.
