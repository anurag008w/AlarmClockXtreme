# Changelog

All notable changes to AlarmClockXtreme will be documented in this file.

## [1.15.43]

- Fixed a web watchdog race where an in-flight stale read could repaint an older alarm state after a create, edit, toggle, or delete had already committed.
- Cross-device alarm state remains durable-first: GitHub HEAD is reconciled before cloud reads, with version/tombstone protection and lightweight Android/web watchdogs.
- Web mutation fencing prevents an older in-flight watchdog response from undoing a just-committed create/edit/toggle/delete on the dashboard.
- Release pipeline trigger refreshed after the final sync-race fix.
- Re-triggered signed release after aligning all v1.15.43 metadata declarations.
- Release: v1.15.43 (versionCode 145).
- Final sync watchdog/release trigger includes durable GitHub-first cross-device reconciliation.


## [1.15.42]

- Added a GitHub commit-baseline guard so a stale web/Render instance cannot overwrite a newer cross-device alarm state.
- Finalized bidirectional alarm-sync reliability fixes: immediate Room watchdog, fast web watchdog, GitHub pull/merge protection, tombstone safety, and stable cross-device alarm identity to prevent edit duplicates.
- Signed-release workflow now requires the Cloud and Android verification gate and a writable release token.
- Release: v1.15.42 (versionCode 144).