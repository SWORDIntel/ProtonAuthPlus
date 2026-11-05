## To be released

### Features :tada:

- YubiKey OATH: batch code calculation in a single USB session, with touch detection to avoid double-touch prompts.
- YubiKey OATH: deleting a hardware-backed entry now frees its slot on the key.
- YubiKey OATH: `YubiKey` badge on hardware-backed entries in the home list.
- YubiKey OATH: busy state during key discovery and typed error messages (locked, timeout, storage full, generic error, verification failed).

### Fixes :bug:

- `Move to YubiKey` now verifies the key's TOTP code against the local seed before erasing the local seed.
- Editing a hardware-backed entry no longer strips the YubiKey backend and overwrites it with an empty secret.
- Hardware-backed entries are no longer synced to Proton cloud with empty secrets.
- Export and automatic backup skip hardware-backed entries instead of failing permanently.
- Pasted Base32 secrets with spaces/dashes/lowercase are normalised; invalid secrets show an error instead of crashing.
- A corrupt hardware row no longer crashes the entries list or stops observation of all entries.
- Removed `fallbackToDestructiveMigration()` so a missing Room migration can no longer silently wipe local data.
- `verifyFdroidProdRelease.sh` auto-detects the Android SDK and checks release signing up front.

### Other

- Hardware entry name/issuer/note are encrypted at rest (`encrypted_content`); legacy plaintext rows are still read.
- Removed the "export/backup without password" actions.
- Declared optional `android.hardware.usb.host`; migrated to AGP 8.9 `androidResources.localeFilters`.
- Added unit tests for entries, entry codes, deletion, persistence and home master.

## 1.3.7 (2026-03-27)

### Fixes :bug:

- Fix QR code scanning.
- Fix snackbar message shown multiple times.
- Fix item creation when account key is changed.

### Other

- Improve global performance if there are a lot of TOTP items.
- Translation updates.

## 1.3.6 (2026-01-19)

### Other

- Biometric unlock improvements.
- Updated core libraries.
- Translation updates.

## 1.3.5 (2025-11-26)

### Other

- Fdroid fixes

## 1.3.4 (2025-11-12)

### Other

- Fdroid release

## 1.3.3 (2025-11-04)

### Fixes :bug:

- Bug fixes and improvements.

## 1.3.2 (2025-10-31)

### Fixes :bug:

- Bug fixes and improvements.

### Other

- Enforce encrypted backups for enhanced security.
- Updated core libraries.
- Translation updates.

## 1.3.1 (2025-09-22)

### Fixes :bug:

- Fix incorrect codes on sorting.

## 1.3.0 (2025-09-19)

### Features :tada:

- Support FIDO2.
- New sorting options
- Support enabling/disabling anonymous data sharing in settings screen

### Fixes :bug:

- Fix RTL support.
- More bug fixes.

### Other

- Importer and Uri parsing improvements.
- Translation updates.

## 1.2.0 (2025-08-20)

### Features :tada:

- Generate password-protected backups.
- Import password-protected backups.
- Import from Google Authenticator via QR code.

### Fix :bug:

- More importer improvements.

### Other

- Translation updates.

## 1.1.2 (2025-08-09)

### Fix :bug:

- More importer improvements.

## 1.1.1 (2025-08-05)

### Fix :bug:

- Improved importers for 2FAS, Ente and Google Authenticator.

## 1.1.0 (2025-07-31)

### Features :tada:

- Improved QR code scanning for Google Authenticator exports.

### Fix :bug:

- Biometric unlock improved.
- Light/dark mode fixes.

## 1.0.0 (2025-07-31)

### Features :tada:

- First release
