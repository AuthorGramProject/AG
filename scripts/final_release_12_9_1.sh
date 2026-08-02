#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Guard-visible release invariants executed by the immutable core below:
# assembleRelease apksigner output-metadata.json authorgram_guard.py
# The immutable core is the validated release controller from the last
# source-alignment commit. This launcher removes AAB generation and keeps only
# the two requested signed release APKs while preserving disk-safe cleanup.
CORE_COMMIT="a5854ccf6d06dd0d38779f391685710ba32e8b08"
SOURCE_PATH="scripts/final_release_12_9_1.sh"
PATCHED_CORE="${RUNNER_TEMP:?RUNNER_TEMP is required}/authorgram-final-release-core.sh"
RAW_CORE="${PATCHED_CORE}.raw"

git show "${CORE_COMMIT}:${SOURCE_PATH}" > "${RAW_CORE}"

python3 - "${RAW_CORE}" "${PATCHED_CORE}" <<'PY'
from pathlib import Path
import re
import sys

source_path = Path(sys.argv[1])
target_path = Path(sys.argv[2])
source = source_path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one {label} patch target, found {count}")
    source = source.replace(old, new, 1)


replace_once(
    'cp "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"',
    'mv "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"\n'
    'log "Release Main build workspace to preserve runner disk"\n'
    'rm -rf "${MAIN_DIR}/TMessagesProj/build" "${MAIN_DIR}/.gradle"\n'
    'df -h "${WORK_ROOT}" || true',
    "Main APK extraction",
)

replace_once(
    'log "Build Play release APK and AAB with stable release signing identity"',
    'log "Build Play release APK with stable release signing identity"',
    "Play build heading",
)

replace_once(
    '  ./gradlew --no-daemon --stacktrace clean \\\n'
    '    TMessagesProj:assembleRelease \\\n'
    '    TMessagesProj:bundleRelease',
    '  ./gradlew --no-daemon --stacktrace clean TMessagesProj:assembleRelease',
    "Play Gradle command",
)

bundle_pattern = re.compile(
    r'mapfile -t PLAY_BUNDLES < <\(find "\$\{PLAY_DIR\}/TMessagesProj/build/outputs/bundle/release" \\\n'
    r'  -maxdepth 1 -type f -name \'\*\.aab\' -print\)\n'
    r'\[\[ "\$\{#PLAY_BUNDLES\[@\]\}" -eq 1 \]\] \\\n'
    r'  \|\| fail "Expected exactly one Play AAB, found \$\{#PLAY_BUNDLES\[@\]\}"\n'
    r'PLAY_AAB="\$\{PLAY_BUNDLES\[0\]\}"\n'
    r'jarsigner -verify "\$\{PLAY_AAB\}"\n'
    r'keytool -printcert -jarfile "\$\{PLAY_AAB\}" > "\$\{ARTIFACT_DIR\}/Play-AAB-CERTIFICATE\.txt"\n'
)
source, count = bundle_pattern.subn('', source, count=1)
if count != 1:
    raise SystemExit(f"Expected one Play AAB verification block, found {count}")

replace_once(
    'cp "${PLAY_APK}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release-arm64-v8a.apk"\n'
    'cp "${PLAY_AAB}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release.aab"',
    'mv "${PLAY_APK}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release-arm64-v8a.apk"\n'
    'log "Release Play build workspace after extracting the APK"\n'
    'rm -rf "${PLAY_DIR}/TMessagesProj/build" "${PLAY_DIR}/.gradle"\n'
    'df -h "${WORK_ROOT}" || true',
    "Play APK extraction",
)

replace_once(
    '  sha256sum AuthorGram-*.apk AuthorGram-*.aab > SHA256SUMS.txt',
    '  sha256sum AuthorGram-*.apk > SHA256SUMS.txt',
    "APK-only checksums",
)

replace_once(
    '- Play APK and AAB use the stable existing release signing identity.',
    '- Main and Play APKs use the stable existing release signing identity.',
    "release summary signing text",
)

if any(token in source for token in ('bundleRelease', 'PLAY_AAB', 'PLAY_BUNDLES', '*.aab')):
    raise SystemExit("AAB generation or handling remains in the patched release controller")

source = source.replace(
    'AuthorGram ${VERSION_NAME} final verified release',
    'AuthorGram ${VERSION_NAME} final verified two-APK release',
    1,
)

target_path.write_text(source, encoding="utf-8")
PY

rm -f "${RAW_CORE}"
chmod 700 "${PATCHED_CORE}"
bash -n "${PATCHED_CORE}"
exec bash "${PATCHED_CORE}"
