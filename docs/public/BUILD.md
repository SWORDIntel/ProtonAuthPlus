# Build, install, and release

The most straightforward way to build and run this application locally is to:

- Install Android Studio: https://developer.android.com/studio/install
- Clone the repository. You have two options:
    - Use the `Project from version control` in Android Studio, or
    - Use the `git clone` command and import it into Android Studio
- Build and run the app directly in Android Studio.

Alternatively, if you want to build from the command line, install the command
line tools from: https://developer.android.com/studio#cmdline-tools. Then
install the SDK using `sdkmanager`. After cloning the repository, edit
`local.properties` so that it points to the SDK location. Common SDK locations
are:

- Windows: `C:\Users\<username>\AppData\Local\Android\sdk`
- MacOS: `/Users/<username>/Library/Android/Sdk/`
- Linux: `/home/<username>/Android/Sdk/`

## Debug install

For local testing on a connected device or emulator, run:

```shell
./gradlew :app:installFdroidProdDebug
```

This installs `com.swordintel.protonauthplus.fdroid` with a debug signing key.

## Signed F-Droid release verification

The user-facing APK is the signed F-Droid production release. It uses the
`com.swordintel.protonauthplus.fdroid` application id.

Release builds require the ProtonAuthPlus release signing configuration. Provide
the signing values through `PROTON_AUTH_PLUS_*` environment variables or an
ignored `private.properties` file; do not commit keystores, passwords,
`private.properties`, or generated signed artifacts.

To build the signed F-Droid production release APK and print the release facts
needed for distribution checks, run:

```shell
./scripts/verifyFdroidProdRelease.sh
```

The script runs Gradle without the configuration cache or build cache, forces
the `:app:assembleFdroidProdRelease` tasks, verifies the APK signature, and
prints the package id, APK SHA-256, and signer certificate SHA-256. The APK is
produced at
`app/build/outputs/apk/fdroidProd/release/app-fdroid-prod-release.apk`.

## Update behavior

Android updates only work when the installed app and the new APK have the same
application id and signing key. If the signing key changes, Android will reject
the update.

Uninstalling first removes local app data, so export or back up software-stored
entries before replacing an install. YubiKey-backed entries cannot export seeds
from the phone; keep account recovery codes or a second enrolled hardware key.

## Release checklist

- Run `./scripts/verifyFdroidProdRelease.sh`.
- Install the APK on a clean device and verify onboarding, entry creation,
  export/backup password enforcement, and YubiKey-backed code generation if the
  release includes YubiKey changes.
- Confirm the printed package id, APK SHA-256, and signer certificate SHA-256
  match the intended release record.
- Publish only the signed APK and public release notes.
