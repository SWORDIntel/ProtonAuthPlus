# Proton Authenticator +

This is an unofficial hardened fork of Proton Authenticator for Android.

It is not the official Proton Authenticator distribution and it is not published
through Google Play from this repository. Build from source or install APKs only
from a release channel you trust.

The upstream project remains Proton Authenticator by Proton AG. This fork keeps
the upstream GPLv3 license and copyright notices while adding security-focused
changes under the `Proton Authenticator +` app label.

## Package and signing

This fork uses its own Android application id base:

```text
com.swordintel.protonauthplus
```

The F-Droid flavored release APK builds as:

```text
com.swordintel.protonauthplus.fdroid
```

Android treats each application id as a separate app. The F-Droid release build
does not replace the official Proton Authenticator app or the Play-flavored
build; migrate or import entries explicitly when moving between apps.

## Install and update

For day-to-day testing, install a debug build from Android Studio or run:

```shell
./gradlew :app:installFdroidProdDebug
```

For user-facing APKs, build and verify the signed F-Droid production release:

```shell
./scripts/verifyFdroidProdRelease.sh
```

The APK is written to `app/build/outputs/apk/fdroidProd/release/`.

Updates require the new APK to use the same application id and signing key as
the installed app. A differently signed APK cannot update an existing install;
Android will require uninstalling the old app first, which removes local app
data unless you have exported or backed it up. Hardware-backed YubiKey entries
cannot be exported from the phone, so keep account recovery codes or a second
enrolled hardware key before replacing installs.

## Hardened fork upgrades

This fork is aimed at reducing phone-resident TOTP seed exposure for high-risk
use cases, especially runtime plaintext seed material in app memory.

- Always-on `FLAG_SECURE` blocks screenshots and insecure screen capture for the
  app UI.
- Exports and automatic backups require a password and no longer allow
  plaintext/no-password artifacts.
- Password-protected export and backup flows are enforced before file
  generation, closing the old plaintext export/backup footgun.
- Hardware-backed YubiKey OATH entries are supported over USB CCID.
- Manual TOTP creation can write the secret directly to an inserted YubiKey with
  `Store on YubiKey`.
- QR/gallery TOTP enrollment can route scanned `otpauth://totp/...` URIs into
  the inserted YubiKey instead of storing the seed locally.
- Existing local TOTP entries can be migrated from the edit screen with
  `Move to YubiKey`.
- YubiKey-backed entries store only local display/sort metadata and the OATH
  credential id. The TOTP seed is not retained in Room and is not decrypted into
  app memory for code generation.
- The hardware-backed path removes the old local-entry runtime plaintext seed
  exposure: code generation asks the YubiKey OATH app for codes instead of
  loading the TOTP secret into the Android process.
- The home screen presentation model no longer retains the full decrypted local
  entry model, avoiding an extra long-lived UI copy of local secrets and
  secret-bearing `otpauth://` URIs.
- YubiKey-backed entries are excluded from export and backup because the phone
  cannot and should not export their seeds.
- If the YubiKey is removed, hardware-backed codes fail closed and render blank
  placeholder codes instead of retaining stale valid OTPs.

## Important limits

Local software-stored TOTP entries still need the secret in process memory while
generating codes. This fork reduces extra UI-layer retention of decrypted entry
objects, but true seed non-exposure requires migrating the entry to a hardware
backend such as YubiKey OATH.

Hardware-backed entries deliberately cannot be exported or backed up by the
phone because the seed is no longer phone-resident. Keep an independent recovery
plan for any account moved to the YubiKey.

See [docs/hardening-yubikey-oath.md](./docs/hardening-yubikey-oath.md) for the
implementation notes and remaining polish work.

## Build and release docs

If you want to build the app locally, please refer to the [BUILD.md](./docs/public/BUILD.md) file.

## Develop

If you want to contribute to the application, please refer to the [CONTRIBUTING.md](CONTRIBUTING.md) file.

## Help us translate

If you want to help us to translate the application, you can learn more about it on [our blog post](https://proton.me/blog/translation-community).

## License

The code and data files in this distribution are licensed under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See <https://www.gnu.org/licenses/> for a copy of this license.

See [LICENSE](LICENSE) file
