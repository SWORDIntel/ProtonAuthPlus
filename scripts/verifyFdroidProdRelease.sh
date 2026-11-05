#!/bin/bash

#
# Copyright (c) 2025 Proton AG
# This file is part of Proton AG and Proton Authenticator.
#
# Proton Authenticator is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# Proton Authenticator is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with Proton Authenticator.  If not, see <https://www.gnu.org/licenses/>.
#

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)
REPO_ROOT=$(cd -- "${SCRIPT_DIR}/.." && pwd)
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${SDK_ROOT}" || ! -d "${SDK_ROOT}" ]]; then
    for candidate in "$HOME/Android/Sdk" "/opt/android-sdk" "/usr/lib/android-sdk"; do
        if [[ -d "${candidate}" ]]; then
            SDK_ROOT="${candidate}"
            break
        fi
    done
fi
SDK_ROOT="${SDK_ROOT:-/opt/android-sdk}"
BUILD_TOOLS_DIR="${SDK_ROOT}/build-tools"
APK_PATH="${REPO_ROOT}/app/build/outputs/apk/fdroidProd/release/app-fdroid-prod-release.apk"

latest_build_tools_version() {
    find "${BUILD_TOOLS_DIR}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' |
        sort -V |
        tail -n 1
}

if [[ ! -d "${BUILD_TOOLS_DIR}" ]]; then
    echo "No Android build-tools found under ${BUILD_TOOLS_DIR}" >&2
    exit 1
fi

BUILD_TOOLS_VERSION=$(latest_build_tools_version)
if [[ -z "${BUILD_TOOLS_VERSION}" ]]; then
    echo "No Android build-tools found under ${BUILD_TOOLS_DIR}" >&2
    exit 1
fi

AAPT="${BUILD_TOOLS_DIR}/${BUILD_TOOLS_VERSION}/aapt"
APKSIGNER="${BUILD_TOOLS_DIR}/${BUILD_TOOLS_VERSION}/apksigner"

if [[ ! -x "${AAPT}" || ! -x "${APKSIGNER}" ]]; then
    echo "Missing aapt or apksigner in ${BUILD_TOOLS_DIR}/${BUILD_TOOLS_VERSION}" >&2
    exit 1
fi

if ! command -v sha256sum > /dev/null 2>&1; then
    echo "Missing required tool: sha256sum" >&2
    exit 1
fi

export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"

# Release signing pre-flight verification
PRIVATE_PROPERTIES="${REPO_ROOT}/private.properties"
missing_signing_vars=()
for var in PROTON_AUTH_PLUS_STORE_FILE PROTON_AUTH_PLUS_STORE_PASSWORD PROTON_AUTH_PLUS_KEY_ALIAS PROTON_AUTH_PLUS_KEY_PASSWORD; do
    val="${!var:-}"
    if [[ -z "${val}" && -f "${PRIVATE_PROPERTIES}" ]]; then
        val=$(grep -E "^[[:space:]]*${var}=" "${PRIVATE_PROPERTIES}" 2>/dev/null | cut -d'=' -f2- | tr -d '\r\n[:space:]' || true)
    fi
    if [[ -z "${val}" ]]; then
        missing_signing_vars+=("${var}")
    fi
done

if [[ ${#missing_signing_vars[@]} -gt 0 ]]; then
    echo "Error: Release signing credentials are not configured." >&2
    echo "Missing: ${missing_signing_vars[*]}" >&2
    echo "" >&2
    echo "Before invoking ./gradlew assembleFdroidProdRelease, please provide release signing credentials via:" >&2
    echo "1. Environment variables:" >&2
    echo "   export PROTON_AUTH_PLUS_STORE_FILE=\"/path/to/keystore.jks\"" >&2
    echo "   export PROTON_AUTH_PLUS_STORE_PASSWORD=\"<store-password>\"" >&2
    echo "   export PROTON_AUTH_PLUS_KEY_ALIAS=\"<key-alias>\"" >&2
    echo "   export PROTON_AUTH_PLUS_KEY_PASSWORD=\"<key-password>\"" >&2
    echo "" >&2
    echo "2. Or define them in ${PRIVATE_PROPERTIES}:" >&2
    echo "   PROTON_AUTH_PLUS_STORE_FILE=/path/to/keystore.jks" >&2
    echo "   PROTON_AUTH_PLUS_STORE_PASSWORD=<store-password>" >&2
    echo "   PROTON_AUTH_PLUS_KEY_ALIAS=<key-alias>" >&2
    echo "   PROTON_AUTH_PLUS_KEY_PASSWORD=<key-password>" >&2
    exit 1
fi

extract_package_id() {
    local badging=$1

    sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "${badging}" |
        sed -n '1p'
}

extract_cert_sha256() {
    local cert_output=$1

    sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' <<< "${cert_output}" |
        sed -n '1p'
}

cd "${REPO_ROOT}"

rm -rf "${REPO_ROOT}/app/build"

./gradlew \
    --no-configuration-cache \
    --no-build-cache \
    --rerun-tasks \
    :app:assembleFdroidProdRelease

if [[ ! -f "${APK_PATH}" ]]; then
    echo "Expected APK was not produced: ${APK_PATH}" >&2
    exit 1
fi

"${APKSIGNER}" verify "${APK_PATH}" > /dev/null

BADGING_OUTPUT=$("${AAPT}" dump badging "${APK_PATH}")
CERT_OUTPUT=$("${APKSIGNER}" verify --print-certs "${APK_PATH}")
PACKAGE_ID=$(extract_package_id "${BADGING_OUTPUT}")
VERSION_LINE=$(sed -n '1p' <<< "${BADGING_OUTPUT}")
VERSION_CODE=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<< "${VERSION_LINE}")
VERSION_NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<< "${VERSION_LINE}")
APK_SHA256=$(sha256sum "${APK_PATH}" | awk '{ print $1 }')
CERT_SHA256=$(extract_cert_sha256 "${CERT_OUTPUT}")

if [[ -z "${PACKAGE_ID}" ]]; then
    echo "Could not read package id from APK: ${APK_PATH}" >&2
    exit 1
fi

if [[ -z "${CERT_SHA256}" ]]; then
    echo "Could not read signer certificate SHA-256 from APK: ${APK_PATH}" >&2
    exit 1
fi

cat <<EOF
APK: ${APK_PATH}
Package id: ${PACKAGE_ID}
Version: ${VERSION_NAME} / ${VERSION_CODE}
APK SHA-256: ${APK_SHA256}
Cert SHA-256: ${CERT_SHA256}
EOF
