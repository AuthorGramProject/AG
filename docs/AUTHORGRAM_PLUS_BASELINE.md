# AuthorGram+ stable feature baseline

## Canonical base

- Runtime base: `play-market` commit `7ab036f0d515126774c9f554dbdbc7213a7f26a3`.
- `dev` is rebuilt from that exact stable Play source.
- Main/AuthorGram+ package: `fork.risin42.nagramx`.
- Play package remains: `toss.authorgram.apk`.
- Signing configuration and release signing identity must remain unchanged.
- AuthorGram+ display name is applied only on `dev` build resources.

## Critical runtime features that must be preserved in full

### Spy settings and navigation

Full source implementations:

- `TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java`
- `TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java`
- `TMessagesProj/src/main/java/toss/authorgram/settings/GhostModeActivity.java`
- `TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java`
- `TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsRouter.java`
- `TMessagesProj/src/main/java/toss/authorgram/filters/AGFiltersSettingsActivity.java`

The Spy page includes Ghost Mode, regex filters, Ayu Spy settings, deleted-message appearance controls, Local Premium, proxy sponsor hiding and sponsored-message hiding.

### Local Premium

Full source implementations and configuration:

- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java`
- `TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPremiumStatusHelper.kt`
- `TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPeerColorHelper.kt`
- `TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/UserObject.java`

Do not replace Local Premium with a UI-only toggle. Preserve all runtime checks and local emoji/status behavior.

### Telegram sponsored-content blocking

Full configuration/policy entry points:

- `TMessagesProj/build.gradle`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java`
- `TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java`

For `fork.risin42.nagramx`, `TELEGRAM_AD_BLOCKING_ENABLED` is enabled because it is a non-Play package. The Play branch/package must continue to keep Play publication restrictions.

### Deleted and edited message retention

Full source/configuration entry points:

- `TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/NaConfig.kt`
- `TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuSavePreferences.java`
- `TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuMessagesController.java`
- `TMessagesProj/src/main/java/com/radolyn/ayugram/database/AyuData.java`
- `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuMessageUtils.java`
- `TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/ChatObject.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/SecretChatHelper.java`

Critical configuration keys include:

- `EnableSaveDeletedMessages`
- `EnableSaveEditsHistory`
- `SaveLocalLastSeen`
- `SaveDeletedMessageForBot`
- `SaveDeletedMessageForBotUser`
- `MessageSavingSaveMedia`

Preserve database import/export, attachment-folder selection, attachment retention and deleted-message appearance controls.

## Branch policy

Only these branches are allowed to remain:

- `play-market` — stable Play Market version.
- `dev` — AuthorGram+ rebuilt from the stable Play source and using the old Main package.

Do not merge the deleted historical `main` branch back into `dev`. Future Main/AuthorGram+ work starts from `dev` and must keep Play runtime stability unless a change is explicitly scoped to AuthorGram+.
