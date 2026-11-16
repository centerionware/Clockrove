# M7.3 native deletion-resolution follow-up

_Status as of 2026-11-16. Source is rebased on main `ac82ec5`; exact-head Actions validation is pending._

This source follow-up adds `changedCount`, `deletedCount`, initial dependent-reference conflict count, and initial session-overlap count to the native preview contract and Dart parser. Summary categories may overlap; deletion markers include historical tombstone revisions.

For supported task comments and attachments, the preview now exposes explicit backup-deletion and restore choices. Apply rechecks the selected graph, entity identity, owning job, current revisions and preview state inside the native transaction. Applying a backup deletion removes comment/image content through `TaskConversationStore`, then records a merge tombstone with both local and backup deletion revisions as parents. Restoring content records a live merge head with both the local tombstone and backup live revision as parents. Unsupported deletion kinds continue to have no actionable choice.

Regression coverage includes the overlapping summary counts; comment deletion with an attached image; restoration of comment and image data; retaining a local deletion; preserving an unrelated card edit; rejecting mismatched comment/attachment choices before writes; and checking both revision parents. This source change has not been built or tested locally. The exact-head GitHub Actions run is the validation gate.

This does not complete M7.3. The Settings/SAF restore route, Dart import/apply coordinator, user-facing review flow, and end-to-end restore acceptance remain outstanding. M7.6 and owner-device acceptance also remain separate gates.
