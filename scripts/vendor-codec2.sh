#!/usr/bin/env bash
#
# Copyright (c) 2026 Meshtastic LLC
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# Refreshes core/codec2/native/codec2 from a Codec2 checkout: copies only the speech codec sources (no FreeDV
# modems), generates the codebook tables the upstream build would generate, and writes codec2/version.h.
#
# Usage: scripts/vendor-codec2.sh <path to a drowe67/codec2 checkout at CODEC2_TAG>
set -euo pipefail

CODEC2_TAG="1.2.0"
SRC_ROOT="${1:?usage: $0 <codec2 checkout>}"
SRC="$SRC_ROOT/src"
CB="$SRC/codebook"
DEST="$(cd "$(dirname "$0")/.." && pwd)/core/codec2/native/codec2"

SOURCES=(codec2 codec2_fft kiss_fft kiss_fftr lpc nlp postfilter sine interp lsp mbest newamp1 phase quantise pack)
HEADERS=(_kiss_fft_guts bpf bpfb codec2 codec2_fft codec2_internal comp comp_prim debug_alloc defines dump interp
    kiss_fft kiss_fftr lpc lsp machdep mbest newamp1 newamp2 nlp os phase postfilter quantise sine)

rm -rf "$DEST"
mkdir -p "$DEST/codec2"
for f in "${SOURCES[@]}"; do cp "$SRC/$f.c" "$DEST/"; done
for f in "${HEADERS[@]}"; do cp "$SRC/$f.h" "$DEST/"; done
cp "$SRC_ROOT/COPYING" "$DEST/COPYING"

sed -e "s/@CODEC2_VERSION_MAJOR@/${CODEC2_TAG%%.*}/" \
    -e "s/@CODEC2_VERSION_MINOR@/$(echo "$CODEC2_TAG" | cut -d. -f2)/" \
    -e "s/#cmakedefine CODEC2_VERSION_PATCH @CODEC2_VERSION_PATCH@/#define CODEC2_VERSION_PATCH $(echo "$CODEC2_TAG" | cut -d. -f3)/" \
    -e "s/@CODEC2_VERSION@/$CODEC2_TAG/" \
    "$SRC_ROOT/cmake/version.h.in" > "$DEST/codec2/version.h"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cc -O2 -o "$TMP/generate_codebook" "$SRC/generate_codebook.c" -lm
gen() { local name="$1" out="$2"; shift 2; "$TMP/generate_codebook" "$name" "$@" > "$DEST/$out"; }
gen lsp_cb codebook.c "$CB"/lsp{1..10}.txt
gen lsp_cbd codebookd.c "$CB"/dlsp{1..10}.txt
gen lsp_cbjmv codebookjmv.c "$CB"/lspjmv{1..3}.txt
gen ge_cb codebookge.c "$CB/gecb.txt"
gen newamp1vq_cb codebooknewamp1.c "$CB/train_120_1.txt" "$CB/train_120_2.txt"
gen newamp1_energy_cb codebooknewamp1_energy.c "$CB/newamp1_energy_q.txt"

cat > "$DEST/README.md" <<EOF
# Vendored Codec2 $CODEC2_TAG

Speech codec subset of https://github.com/drowe67/codec2 (tag \`$CODEC2_TAG\`), LGPL-2.1 (see \`COPYING\`;
\`kiss_fft*\` are BSD-3-Clause by Mark Borgerding). The \`codebook*.c\` tables are generated from upstream's
\`src/codebook/*.txt\` by upstream's \`generate_codebook\`. Do not edit by hand: rerun
\`scripts/vendor-codec2.sh <checkout>\`.
EOF
echo "Vendored Codec2 $CODEC2_TAG into $DEST"
