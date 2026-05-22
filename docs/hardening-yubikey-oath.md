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
  entry before deleting the local seed.

## Required Android pieces

- Use YubiKit Android OATH APIs over USB CCID and NFC.
- Add a UI flow for detecting/selecting a YubiKey.
- Add an enrollment flow for moving an existing entry to hardware.
- Add a recovery flow that strongly encourages enrolling a second hardware key.

## Current implementation status

- `EntryCredentialBackend` separates local encrypted entries from YubiKey OATH
  entries.
- Room schema version 7 adds hardware credential metadata columns.
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
  instead of the local Rust TOTP generator.
- Export and automatic backup refuse hardware-backed entries because the phone
  cannot and should not export their seeds.

The remaining product work is mainly around polish: clearer YubiKey error
messages, duplicate/capacity handling, and optional batch migration. Bulk import
is intentionally still local-only until there is a transactional UX for YubiKey
capacity, duplicate handling, and partial-write recovery.

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
