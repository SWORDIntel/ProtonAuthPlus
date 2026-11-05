# Hardened YubiKey OATH Backend Plan

This fork treats phone-resident TOTP seeds as unacceptable for high-risk
accounts. The target design is to make the YubiKey OATH application the owner
of TOTP secrets and keep only display metadata on the phone.

## Current risk

Local entries are encrypted at rest in `EntryEntity.encrypted_content`, but the
app decrypts them into runtime models containing `secret` to render codes. That
runtime plaintext is the main exposure against a compromised endpoint.

## Target model

- Add a `CredentialBackend` abstraction with at least:
  - `LocalEncryptedBackend` for existing local entries.
  - `YubiKeyOathBackend` for hardware-resident entries.
- YubiKey-backed entries store only metadata locally:
  - local entry id
  - issuer/name/icon/sort order
  - YubiKey OATH credential id/name
  - backend type
- The TOTP seed is written to the YubiKey OATH app once during enrollment or
  migration and is not retained in Room.
- Code generation for YubiKey-backed entries requires USB/NFC access to the key.
- Migration verifies that a YubiKey-generated code matches the existing local
  entry before deleting the local seed (implemented, see below).

## Required Android pieces

- Use YubiKit Android OATH APIs over USB CCID and NFC.
- Add a UI flow for detecting/selecting a YubiKey.
- Add an enrollment flow for moving an existing entry to hardware.
- Add a recovery flow that strongly encourages enrolling a second hardware key.

## Current implementation status

- `EntryCredentialBackend` separates local encrypted entries from YubiKey OATH
  entries.
- Room schema version 7 adds hardware credential metadata columns.
- Hardware entry metadata (name, issuer, note) is encrypted at rest in
  `EntryEntity.encrypted_content` via `EncryptionTag.EntryContent`. The plaintext
  columns (`hardware_name`, `hardware_issuer`, `hardware_note`) are left null on
  new writes to prevent flash storage metadata leaks, while remaining backward-compatible
  with older rows.
- `CreateEntryCommand.FromYubiKeyTotp` writes the TOTP secret to the inserted
  USB YubiKey OATH app, then stores only metadata locally.
- Manual TOTP creation exposes a `Store on YubiKey` option that uses the
  hardware-backed command.
- QR/gallery TOTP creation exposes a key-mode toggle that routes scanned
  `otpauth://totp/...` URIs into the YubiKey OATH app instead of local storage.
- Existing local TOTP entries can be opened in the edit screen and moved to the
  inserted USB YubiKey with the `Move to YubiKey` action. The entry id and sort
  position are preserved, while the local seed payload is replaced with empty
  encrypted content and YubiKey OATH metadata.
- `EntryCodesSearcher` routes `yubikey-oath://` entries to YubiKit over USB
  instead of the local Rust TOTP generator. All hardware entries are resolved in
  a single CCID session via `YubiKeyOathCodeProvider.calculateBatch` (OATH
  `CALCULATE ALL`), instead of one session per entry. Touch-required credentials
  are detected so the "next code" lookup does not trigger a second touch prompt.
- Export and automatic backup skip hardware-backed entries (they are filtered
  out of the payload) because the phone cannot and should not export their
  seeds. This no longer raises a fatal error, so automatic backups of the
  remaining local entries keep working when a YubiKey entry exists.
- Cloud sync (`SyncEntriesModelsUseCase`) only syncs `LocalEncrypted` entries.
  Hardware entries carry no seed, so they are never pushed to Proton as empty
  secrets / `yubikey-oath://` URIs.
- Cloud copy handling on migration: When moving an entry to YubiKey, an explicit
  confirmation dialog is presented: "Do you also want to delete the cloud copy stored
  in Proton Cloud?".
  - The default positive choice is "Delete cloud copy" (`deleteCloudCopy = true`).
    Upon migration verification, the hardware entry retains `isSynced = true` as a
    pending cloud-deletion request. On the next sync, `EntriesSyncer` invokes
    `api.deleteAll` for that remote copy in Proton Cloud and clears `isSynced` to
    `false`.
  - The secondary choice is "Keep cloud copy" (`deleteCloudCopy = false`). The entry
    sets `isSynced = false`, leaving the remote copy in Proton Cloud untouched while
    ensuring it never overwrites the local hardware entry.
- `EntriesSyncer` drops any remote entry whose local id belongs to a local
  hardware entry (including tombstones) before computing sync operations, so a
  cloud copy made before `Move to YubiKey` cannot overwrite the hardware entry.
  Deleting a hardware entry tombstones it (`isDeleted = true`) instead of
  removing the row so that block keeps working.

### Migration safety (`Move to YubiKey`)

`EntryUpdater.migrateToYubiKeyTotp` verifies before it wipes:

1. Write the credential to the YubiKey.
2. Generate the TOTP code locally for the current timestamp.
3. Ask the YubiKey for the code at the same timestamp (`calculateCode`).
4. Only if the two codes match is the local seed replaced by empty encrypted
   content. On mismatch or hardware failure `YubiKeyVerificationFailedException`
   is thrown and the local seed is left untouched.

Editing the name/issuer/note of a hardware entry preserves the
`YubiKeyOath` backend instead of stripping it.

### Typed failure reasons

`CreateEntryReason` / `UpdateEntryReason` expose `YubiKeyLocked`,
`YubiKeyTimeout`, `YubiKeyStorageFull` and `YubiKeyError` (plus
`VerificationFailed` on update). The command handlers map timeouts, APDU status
words (`NO_SPACE`/`MEMORY_ERROR` -> storage full,
`AUTH_METHOD_BLOCKED`/`SECURITY_CONDITION_NOT_SATISFIED` -> locked), I/O errors
and enrollment-lock errors onto them. Pasted Base32 secrets are normalised
(whitespace/dashes stripped, upper-cased) and invalid input maps to
`InvalidEntrySecret` instead of crashing. The manual and scan flows show a busy
state during YubiKey discovery and surface these reasons as snackbars.

### Slot reclamation on delete

Deleting a hardware-backed entry now calls
`YubiKeyOathCredentialDeleter.deleteIfConnected`, which removes the credential
from the key (USB CCID, 5 s timeout) so one of the 32 OATH slots is freed. If
the key is absent or locked, the local row is still deleted and the credential
remains on the key; remove it manually (e.g. with Yubico Authenticator).

### Persistence robustness

- `fallbackToDestructiveMigration()` was removed from the Room module; a
  missing migration now fails loudly instead of silently wiping all entries.
- `RoomEntriesPersistenceDataSource` no longer crashes on corrupt hardware rows:
  a missing credential id or unknown backend type falls back to
  `LocalEncrypted` with a warning, an unknown algorithm defaults to SHA1, and
  `observeAll()` skips (and logs) a single unparseable row instead of
  terminating the whole Flow.

### UI

Hardware-backed cards on the home screen show a `YubiKey` badge
(`HomeMasterEntryModel.isHardwareBacked`). The "Export without password" and
"Enable backup without password" actions were removed; export/backup failures
show explicit error dialogs.

### Build

- `android.hardware.usb.host` is declared as `required="false"`.
- `resourceConfigurations` was replaced by `androidResources.localeFilters`
  (AGP 8.9).
- `scripts/verifyFdroidProdRelease.sh` auto-detects the Android SDK
  (`$ANDROID_SDK_ROOT`, `$ANDROID_HOME`, `~/Android/Sdk`, `/opt/android-sdk`,
  `/usr/lib/android-sdk`) and fails early when release signing is not
  configured. Release tasks require `PROTON_AUTH_PLUS_*` environment variables
  or an ignored `private.properties`.

The remaining product work is optional batch migration and a recovery flow that
encourages enrolling a second key. Bulk import is intentionally still local-only
until there is a transactional UX for YubiKey capacity, duplicate handling, and
partial-write recovery. Note that the OATH credential name/issuer on the key
itself is not encrypted by this app.

## Hardening rules

- No plaintext export path.
- Backups must be password-protected.
- `FLAG_SECURE` is always enabled.
- Seed reveal/export requires explicit user authentication.
- YubiKey-backed entries cannot export a TOTP seed from the phone.
- The home screen presentation model must not retain the full decrypted
  `EntryModel`, because local entries may contain the TOTP seed and a
  secret-bearing `otpauth://` URI. Home cards copy only non-secret display
  metadata plus the current/next code.

## Local software-entry limits

Local TOTP entries still require plaintext seed material briefly during software
code generation: the app must decrypt the entry and feed the secret to the local
TOTP generator. This cannot be fully eliminated without moving the secret into a
hardware-backed backend or a separate isolated code-generation component. The
current best-effort local hardening is to keep plaintext out of long-lived UI
models where it is not needed, avoid plaintext exports/backups, and migrate
high-risk entries to YubiKey OATH.
