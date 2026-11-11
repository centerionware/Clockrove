# Clockrove UI direction

The owner requires a clean, modern interface from the prototype onward. The
reference implementation is `ClockroveTheme` and the timer screen, validated in
Actions run 42996201315 and visually reviewed in both light and dark previews.
It is not a full launch acceptance pass or physical-device result.

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
