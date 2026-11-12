#!/usr/bin/env bash
# Copyright (c) 2026 Meshtastic LLC
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fetches the pinned meshtastic/web release that the phone's "Web access" bridge serves, verifies it against the
# pinned SHA-256, and stores it where the app bundles it. To update: bump VERSION and SHA256 together, run this, then
# update artifactVersion in config/aboutlibraries/libraries/meshtastic-web.json.
set -euo pipefail

VERSION="v2.7.2"
SHA256="62657b85b4c24af4d44da2932b64143abdbf6e65a79fcb51fd0801d9540616e2"
URL="https://github.com/meshtastic/web/releases/download/${VERSION}/build.tar"

root="$(cd "$(dirname "$0")/.." && pwd)"
dest_dir="${root}/core/resources/src/commonMain/composeResources/files/webclient"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

curl -fsSL --retry 3 -o "${tmp}/build.tar" "${URL}"
actual="$(shasum -a 256 "${tmp}/build.tar" | cut -d' ' -f1)"
if [[ "${actual}" != "${SHA256}" ]]; then
  echo "SHA-256 mismatch for ${URL}: expected ${SHA256}, got ${actual}" >&2
  exit 1
fi

mkdir -p "${dest_dir}"
mv "${tmp}/build.tar" "${dest_dir}/build.tar"
printf '%s\n' "${VERSION}" > "${dest_dir}/VERSION"
echo "Web client ${VERSION} stored in ${dest_dir}"
