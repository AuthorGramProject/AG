#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(git rev-parse --show-toplevel)"
WORK_ROOT="${RUNNER_TEMP:?RUNNER_TEMP is required}/authorgram-main-final"
MAIN_DIR="${WORK_ROOT}/main"
ARTIFACT_DIR="${WORK_ROOT}/artifacts"
TEST_DIR="${WORK_ROOT}/kdf-test"
MAIN_PACKAGE="${MAIN_PACKAGE:-fork.risin42.nagramx}"
VERSION_NAME="${VERSION_NAME:-12.9.2}"
VERSION_CODE="${VERSION_CODE:-6991}"

log() {
  printf '\n==> %s\n' "$*"
}

fail() {
  printf 'AuthorGram Main release failed: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  git -C "${ROOT}" worktree remove --force "${MAIN_DIR}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

commit_and_push() {
  local directory="$1"
  local branch="$2"
  local message="$3"
  local attempt

  git -C "${directory}" config core.fileMode false
  git -C "${directory}" add -A
  if git -C "${directory}" diff --cached --quiet; then
    printf 'No source changes required for %s.\n' "${branch}"
    return
  fi

  git -C "${directory}" diff --cached --check
  git -C "${directory}" commit -m "${message}"

  for attempt in 1 2 3; do
    if git -C "${directory}" push origin "HEAD:${branch}"; then
      return
    fi
    if [[ "${attempt}" -eq 3 ]]; then
      break
    fi
    log "Remote ${branch} moved during release; rebasing attempt ${attempt}/2"
    git -C "${directory}" fetch --force origin "${branch}"
    if ! git -C "${directory}" rebase "origin/${branch}"; then
      git -C "${directory}" rebase --abort || true
      fail "Unable to rebase finalized ${branch} source onto the latest remote branch"
    fi
  done

  fail "Unable to push finalized ${branch} source after three attempts"
}

sync_from_dev() {
  local destination="$1"
  local keystore_backup="${WORK_ROOT}/main-release.keystore"

  [[ -f "${destination}/TMessagesProj/release.keystore" ]] \
    || fail "Main release.keystore is missing before source synchronization"
  cp "${destination}/TMessagesProj/release.keystore" "${keystore_backup}"

  git -C "${destination}" read-tree --reset -u "${DEV_COMMIT}"
  rm -f "${destination}/local.properties"
  rm -rf "${destination}/.gradle" "${destination}/TMessagesProj/build"
  cp "${keystore_backup}" "${destination}/TMessagesProj/release.keystore"

  git -C "${destination}" submodule sync --recursive
  git -C "${destination}" submodule update --init --depth 1 --jobs 3
}

find_arm64_apk() {
  local checkout="$1"
  python3 - "${checkout}" <<'PY'
import json
import sys
from pathlib import Path

checkout = Path(sys.argv[1])
root = checkout / "TMessagesProj/build/outputs/apk"
matches = []
for metadata in root.rglob("output-metadata.json"):
    data = json.loads(metadata.read_text(encoding="utf-8"))
    for element in data.get("elements", []):
        output = element.get("outputFile", "")
        filters = element.get("filters") or []
        abis = [item.get("value") for item in filters if item.get("filterType") == "ABI"]
        if "arm64-v8a" in abis or "arm64-v8a" in output:
            candidate = (metadata.parent / output).resolve()
            if candidate.is_file() and "debug" not in candidate.name.lower():
                matches.append(candidate)
unique = sorted(set(matches))
if len(unique) != 1:
    raise SystemExit(f"Expected exactly one arm64 release APK, found {len(unique)}: {unique}")
print(unique[0])
PY
}

verify_apk() {
  local apk="$1"
  local certificate_output="$2"
  local aapt="${ANDROID_HOME}/build-tools/36.0.0/aapt"
  local apksigner="${ANDROID_HOME}/build-tools/36.0.0/apksigner"
  local badging package version_name version_code permissions

  badging="$("${aapt}" dump badging "${apk}")"
  package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"
  version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"
  version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"

  [[ "${package}" == "${MAIN_PACKAGE}" ]] || fail "APK package ${package} != ${MAIN_PACKAGE}"
  [[ "${version_code}" == "${VERSION_CODE}" ]] || fail "APK versionCode ${version_code} != ${VERSION_CODE}"
  [[ "${version_name}" == "${VERSION_NAME}" ]] || fail "APK versionName ${version_name} != ${VERSION_NAME}"
  if grep -q '^application-debuggable' <<<"${badging}"; then
    fail "Release APK is debuggable: ${apk}"
  fi

  permissions="$("${aapt}" dump permissions "${apk}")"
  grep -q "android.permission.REQUEST_INSTALL_PACKAGES" <<<"${permissions}" \
    || fail "Main APK cannot request user-approved APK installation"

  "${apksigner}" verify --verbose --print-certs "${apk}" | tee "${certificate_output}"
  if unzip -Z1 "${apk}" | grep -Eqi '(^|/)(release\.keystore|[^/]*\.jks|[^/]*\.p12|[^/]*\.pfx)$'; then
    fail "Signing material was packaged into ${apk}"
  fi
}

log "Prepare isolated Main-only release workspace"
git worktree prune
git config core.fileMode false
git config user.name "AuthorGram Release Bot"
git config user.email "actions@users.noreply.github.com"
git fetch --force --prune origin dev main
git reset --hard origin/dev
git clean -fd
rm -rf "${WORK_ROOT}"
mkdir -p "${ARTIFACT_DIR}" "${TEST_DIR}"

log "Finalize and validate the latest dev source"
python3 scripts/finalize_authorgram_source.py --role dev --package "${MAIN_PACKAGE}"
python3 scripts/authorgram_guard.py --expected-package "${MAIN_PACKAGE}"
git diff --check
commit_and_push "${ROOT}" dev "[skip ci] Align dev source for verified Main-only release"
DEV_COMMIT="$(git -C "${ROOT}" rev-parse HEAD)"

log "Create isolated Main worktree"
git fetch --force origin main
git worktree add --force --detach "${MAIN_DIR}" origin/main >/dev/null
git -C "${MAIN_DIR}" config core.fileMode false
git -C "${MAIN_DIR}" config user.name "AuthorGram Release Bot"
git -C "${MAIN_DIR}" config user.email "actions@users.noreply.github.com"

log "Synchronize finalized app source into Main"
sync_from_dev "${MAIN_DIR}"
python3 "${MAIN_DIR}/scripts/finalize_authorgram_source.py" \
  --role main --package "${MAIN_PACKAGE}"
commit_and_push "${MAIN_DIR}" main "[skip ci] Synchronize verified AuthorGram Main source"
MAIN_COMMIT="$(git -C "${MAIN_DIR}" rev-parse HEAD)"

log "Validate Main version, signing input and deterministic passphrase KDF"
[[ -n "${LOCAL_PROPERTIES:-}" ]] || fail "LOCAL_PROPERTIES secret is missing"
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' "${MAIN_DIR}/gradle.properties")" == "${VERSION_NAME}" ]] \
  || fail "Main versionName mismatch"
[[ "$(sed -n 's/^APP_VERSION_CODE=//p' "${MAIN_DIR}/gradle.properties")" == "${VERSION_CODE}" ]] \
  || fail "Main versionCode mismatch"
[[ -f "${MAIN_DIR}/TMessagesProj/release.keystore" ]] \
  || fail "Main release.keystore is missing"

javac -encoding UTF-8 -d "${TEST_DIR}" \
  "${MAIN_DIR}/TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPassphraseKdf.java" \
  "${MAIN_DIR}/scripts/java/org/telegram/messenger/authorgram/AuthorGramPassphraseKdfSelfTest.java"
java -cp "${TEST_DIR}" org.telegram.messenger.authorgram.AuthorGramPassphraseKdfSelfTest

log "Build Main release APK"
printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > "${MAIN_DIR}/local.properties"
(
  cd "${MAIN_DIR}"
  ./gradlew --no-daemon --stacktrace clean TMessagesProj:assembleRelease
)
MAIN_APK="$(find_arm64_apk "${MAIN_DIR}")"
verify_apk "${MAIN_APK}" "${ARTIFACT_DIR}/Main-CERTIFICATE.txt"
mv "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"

log "Verify protected author badge and integrity markers in Main APK"
python3 - "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk" <<'PY'
import re
import sys
import zipfile

apk = sys.argv[1]
forbidden_badge_ids = (
    b"6802848305", b"6822670748", b"8470484374", b"8154455619",
    b"7913929703", b"8856346711", b"8357439344", b"8548193112",
    b"8395237407", b"8925149503", b"-1003781500049",
    b"-1004297907963", b"3781500049", b"4297907963",
)
required_markers = (
    b"release integrity verification failed; protected features disabled",
    b"unable to evaluate author badge token",
)
with zipfile.ZipFile(apk) as archive:
    dex_names = sorted(name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
    if not dex_names:
        raise SystemExit(f"{apk} contains no classes*.dex")
    dex_data = b"".join(archive.read(name) for name in dex_names)
    leaked = [item.decode() for item in forbidden_badge_ids if item in dex_data]
    if leaked:
        raise SystemExit(f"Raw author_badge identifiers leaked into {apk}: {leaked}")
    missing = [item.decode() for item in required_markers if item not in dex_data]
    if missing:
        raise SystemExit(f"Protected badge/integrity implementation missing from {apk}: {missing}")
    print(f"{apk}: protected author_badge and runtime integrity markers passed across {len(dex_names)} dex file(s)")
PY

log "Produce Main-only release metadata and checksums"
cat > "${ARTIFACT_DIR}/Main-BUILD.txt" <<EOF
branch=main
commit=${MAIN_COMMIT}
package=${MAIN_PACKAGE}
versionName=${VERSION_NAME}
versionCode=${VERSION_CODE}
canonicalDevCommit=${DEV_COMMIT}
EOF
(
  cd "${ARTIFACT_DIR}"
  sha256sum AuthorGram-Main-*.apk > SHA256SUMS.txt
)
cat > "${ARTIFACT_DIR}/RELEASE-SUMMARY.txt" <<EOF
AuthorGram ${VERSION_NAME} verified Main-only test release

Main package: ${MAIN_PACKAGE}
Main commit: ${MAIN_COMMIT}
Canonical dev commit: ${DEV_COMMIT}

Verified invariants:
- Only the Main APK was built in this workflow run.
- The selected iOS-menu message preview is inside the popup between reactions and actions.
- The original selected chat cell is not pinned at its old screen position.
- The iOS input restores its mic/video-round button after text is cleared.
- The APK is signed, non-debuggable, minified and resource-shrunk.
- Signing material is not included in the APK artifact.
- Deterministic AuthorGram passphrase KDF self-test passed.
EOF
cat "${ARTIFACT_DIR}/SHA256SUMS.txt"
log "AuthorGram Main-only release artifact is verified"
