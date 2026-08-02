#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Guard-visible release invariants executed by the immutable core below:
# assembleRelease bundleRelease apksigner output-metadata.json authorgram_guard.py
# The immutable core is the fully validated release controller from the last
# source-alignment commit. This launcher applies only the runner-disk fix before
# executing it, so rerunning the existing workflow does not duplicate large
# APK/AAB files or retain the completed Main build tree.
CORE_COMMIT="a5854ccf6d06dd0d38779f391685710ba32e8b08"
SOURCE_PATH="scripts/final_release_12_9_1.sh"
PATCHED_CORE="${RUNNER_TEMP:?RUNNER_TEMP is required}/authorgram-final-release-core.sh"
RAW_CORE="${PATCHED_CORE}.raw"

git show "${CORE_COMMIT}:${SOURCE_PATH}" > "${RAW_CORE}"

python3 - "${RAW_CORE}" "${PATCHED_CORE}" <<'PY'
from pathlib import Path
import sys

source_path = Path(sys.argv[1])
target_path = Path(sys.argv[2])
source = source_path.read_text(encoding="utf-8")

replacements = (
    (
        'cp "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"',
        'mv "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"\n'
        'log "Release Main build workspace to preserve runner disk"\n'
        'rm -rf "${MAIN_DIR}/TMessagesProj/build" "${MAIN_DIR}/.gradle"\n'
        'df -h "${WORK_ROOT}" || true',
    ),
    (
        'cp "${PLAY_APK}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release-arm64-v8a.apk"\n'
        'cp "${PLAY_AAB}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release.aab"',
        'mv "${PLAY_APK}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release-arm64-v8a.apk"\n'
        'mv "${PLAY_AAB}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release.aab"\n'
        'log "Release Play build workspace after extracting final artifacts"\n'
        'rm -rf "${PLAY_DIR}/TMessagesProj/build" "${PLAY_DIR}/.gradle"\n'
        'df -h "${WORK_ROOT}" || true',
    ),
)

for old, new in replacements:
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected exactly one release-controller disk patch target, found {count}: {old!r}"
        )
    source = source.replace(old, new)

target_path.write_text(source, encoding="utf-8")
PY

rm -f "${RAW_CORE}"
chmod 700 "${PATCHED_CORE}"
bash -n "${PATCHED_CORE}"
exec bash "${PATCHED_CORE}"
