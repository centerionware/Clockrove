# Task quick share evidence (November 27, 2026)

## Scope

- The task-photo header has a share action whenever task-level images exist. It
  shares only those images, never comment images or comment text.
- Each comment has a share action when it has text or images. It sends the
  comment text and its attached images together; text-only comments use
  `text/plain`.
- The Android host builds `ACTION_SEND` / `ACTION_SEND_MULTIPLE` and a chooser.
  Images are served by the nonexported `TaskShareProvider` through temporary
  read grants, so sharing does not copy image bytes back into Dart.
- New copy is in the `task_conversation.yaml` catalogs for all 17 supported
  languages: share task photos, share comment, and share-menu failure.

## Validation

- `python3 tool/flutter_catalog.py --check` passed after catalog generation.
- `dart format` parsed the changed Dart files.
- `test/task_conversation_test.dart` adds coverage for task-image ids, a
  comment with text and an image, and a text-only comment share.
- Per repository instructions no local Flutter build, analyzer, Android build,
  or device test ran. GitHub Actions is the authoritative validation. The
  chooser, provider grants, and receiving-app behavior have not been verified on
  a device.
