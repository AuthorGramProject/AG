#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(git rev-parse --show-toplevel)"
WORK_ROOT="${RUNNER_TEMP:?RUNNER_TEMP is required}/authorgram-final"
MAIN_DIR="${WORK_ROOT}/main"
PLAY_DIR="${WORK_ROOT}/play"
ARTIFACT_DIR="${WORK_ROOT}/artifacts"
TEST_DIR="${WORK_ROOT}/kdf-test"
MAIN_PACKAGE="${MAIN_PACKAGE:-fork.risin42.nagramx}"
PLAY_PACKAGE="${PLAY_PACKAGE:-toss.authorgram.apk}"
VERSION_NAME="${VERSION_NAME:-12.9.1}"
VERSION_CODE="${VERSION_CODE:-6967}"

log() {
  printf '\n==> %s\n' "$*"
}

fail() {
  printf 'AuthorGram final release failed: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  rm -f "${PLAY_DIR}/TMessagesProj/release.keystore" 2>/dev/null || true
  git -C "${ROOT}" worktree remove --force "${MAIN_DIR}" >/dev/null 2>&1 || true
  git -C "${ROOT}" worktree remove --force "${PLAY_DIR}" >/dev/null 2>&1 || true
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
  rsync -a --delete --quiet \
    --exclude='.git' \
    --exclude='.github/' \
    --exclude='.gradle/' \
    --exclude='local.properties' \
    --exclude='**/build/' \
    --exclude='TMessagesProj/release.keystore' \
    "${ROOT}/" "${destination}/"
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
  local expected_package="$2"
  local certificate_output="$3"
  local aapt="${ANDROID_HOME}/build-tools/36.0.0/aapt"
  local apksigner="${ANDROID_HOME}/build-tools/36.0.0/apksigner"
  local badging package version_name version_code

  badging="$("${aapt}" dump badging "${apk}")"
  package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"
  version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"
  version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"

  [[ "${package}" == "${expected_package}" ]] || fail "APK package ${package} != ${expected_package}"
  [[ "${version_code}" == "${VERSION_CODE}" ]] || fail "APK versionCode ${version_code} != ${VERSION_CODE}"
  [[ "${version_name}" == "${VERSION_NAME}" ]] || fail "APK versionName ${version_name} != ${VERSION_NAME}"
  if grep -q '^application-debuggable' <<<"${badging}"; then
    fail "Release APK is debuggable: ${apk}"
  fi

  "${apksigner}" verify --verbose --print-certs "${apk}" | tee "${certificate_output}"
  if unzip -Z1 "${apk}" | grep -Eqi '(^|/)(release\.keystore|[^/]*\.jks|[^/]*\.p12|[^/]*\.pfx)$'; then
    fail "Signing material was packaged into ${apk}"
  fi
}

log "Prepare isolated release workspace"
git worktree prune
git config core.fileMode false
git config user.name "AuthorGram Release Bot"
git config user.email "actions@users.noreply.github.com"
git fetch --force --prune origin dev main play-market
git reset --hard origin/dev
git clean -fd
rm -rf "${WORK_ROOT}"
mkdir -p "${ARTIFACT_DIR}" "${TEST_DIR}"

log "Finalize and validate the latest dev source"
python3 scripts/finalize_authorgram_source.py --role dev --package "${MAIN_PACKAGE}"
python3 scripts/authorgram_guard.py --expected-package "${MAIN_PACKAGE}"
git diff --check
commit_and_push "${ROOT}" dev "[skip ci] Align dev source for final AuthorGram release"
DEV_COMMIT="$(git -C "${ROOT}" rev-parse HEAD)"

log "Create isolated Main and Play worktrees"
git fetch --force origin main play-market
git worktree add --force --detach "${MAIN_DIR}" origin/main >/dev/null
git worktree add --force --detach "${PLAY_DIR}" origin/play-market >/dev/null
for checkout in "${MAIN_DIR}" "${PLAY_DIR}"; do
  git -C "${checkout}" config core.fileMode false
  git -C "${checkout}" config user.name "AuthorGram Release Bot"
  git -C "${checkout}" config user.email "actions@users.noreply.github.com"
done

log "Synchronize finalized app source into Main"
sync_from_dev "${MAIN_DIR}"
python3 "${MAIN_DIR}/scripts/finalize_authorgram_source.py" \
  --role main --package "${MAIN_PACKAGE}"
commit_and_push "${MAIN_DIR}" main "[skip ci] Synchronize finalized AuthorGram Main source"

log "Synchronize finalized app source into Play Market"
sync_from_dev "${PLAY_DIR}"
rm -f "${PLAY_DIR}/TMessagesProj/release.keystore"
python3 "${PLAY_DIR}/scripts/finalize_authorgram_source.py" \
  --role play --package "${PLAY_PACKAGE}"
commit_and_push "${PLAY_DIR}" play-market "[skip ci] Synchronize finalized AuthorGram Play source"

log "Verify Main and Play application-source parity"
git fetch --force origin main play-market
python3 scripts/authorgram_parity_guard.py \
  --main-ref origin/main \
  --play-ref origin/play-market

MAIN_COMMIT="$(git -C "${MAIN_DIR}" rev-parse HEAD)"
PLAY_COMMIT="$(git -C "${PLAY_DIR}" rev-parse HEAD)"

log "Validate versions, signing inputs and deterministic passphrase KDF"
[[ -n "${LOCAL_PROPERTIES:-}" ]] || fail "LOCAL_PROPERTIES secret is missing"
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' "${MAIN_DIR}/gradle.properties")" == "${VERSION_NAME}" ]] \
  || fail "Main versionName mismatch"
[[ "$(sed -n 's/^APP_VERSION_CODE=//p' "${MAIN_DIR}/gradle.properties")" == "${VERSION_CODE}" ]] \
  || fail "Main versionCode mismatch"
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' "${PLAY_DIR}/gradle.properties")" == "${VERSION_NAME}" ]] \
  || fail "Play versionName mismatch"
[[ "$(sed -n 's/^APP_VERSION_CODE=//p' "${PLAY_DIR}/gradle.properties")" == "${VERSION_CODE}" ]] \
  || fail "Play versionCode mismatch"
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
verify_apk "${MAIN_APK}" "${MAIN_PACKAGE}" "${ARTIFACT_DIR}/Main-CERTIFICATE.txt"
mv "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"
log "Release Main build workspace to preserve runner disk"
rm -rf "${MAIN_DIR}/TMessagesProj/build" "${MAIN_DIR}/.gradle"
df -h "${WORK_ROOT}" || true

log "Build Play release APK with stable release signing identity"
cp "${MAIN_DIR}/TMessagesProj/release.keystore" "${PLAY_DIR}/TMessagesProj/release.keystore"
printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > "${PLAY_DIR}/local.properties"
(
  cd "${PLAY_DIR}"
  ./gradlew --no-daemon --stacktrace clean TMessagesProj:assembleRelease
)
PLAY_APK="$(find_arm64_apk "${PLAY_DIR}")"
verify_apk "${PLAY_APK}" "${PLAY_PACKAGE}" "${ARTIFACT_DIR}/Play-CERTIFICATE.txt"
mv "${PLAY_APK}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release-arm64-v8a.apk"
rm -f "${PLAY_DIR}/TMessagesProj/release.keystore"
log "Release Play build workspace after extracting the APK"
rm -rf "${PLAY_DIR}/TMessagesProj/build" "${PLAY_DIR}/.gradle"
df -h "${WORK_ROOT}" || true

log "Produce release metadata and checksums"
cat > "${ARTIFACT_DIR}/Main-BUILD.txt" <<EOF
branch=main
commit=${MAIN_COMMIT}
package=${MAIN_PACKAGE}
versionName=${VERSION_NAME}
versionCode=${VERSION_CODE}
canonicalDevCommit=${DEV_COMMIT}
EOF
cat > "${ARTIFACT_DIR}/Play-BUILD.txt" <<EOF
branch=play-market
commit=${PLAY_COMMIT}
package=${PLAY_PACKAGE}
versionName=${VERSION_NAME}
versionCode=${VERSION_CODE}
canonicalDevCommit=${DEV_COMMIT}
EOF
(
  cd "${ARTIFACT_DIR}"
  sha256sum AuthorGram-*.apk > SHA256SUMS.txt
)
cat > "${ARTIFACT_DIR}/RELEASE-SUMMARY.txt" <<EOF
AuthorGram ${VERSION_NAME} final verified two-APK release

Main package: ${MAIN_PACKAGE}
Play package: ${PLAY_PACKAGE}
Main commit: ${MAIN_COMMIT}
Play commit: ${PLAY_COMMIT}
Canonical dev commit: ${DEV_COMMIT}

Verified invariants:
- Main and Play application source are synchronized.
- The only application identity difference is APP_PACKAGE.
- Main and Play artifact names are selected from the package in common Gradle source.
- Encrypted-message replies cannot carry plaintext quote text or quote entities.
- Legacy visible Nagram/Nekogram branding is rejected except exact legal upstream attribution.
- Both APKs are signed, non-debuggable, minified and shrink resources.
- Main and Play APKs use the stable existing release signing identity.
- Signing material is not included in APK artifacts or committed to Play source.
- Deterministic AuthorGram passphrase KDF self-test passed.
- No Android App Bundle was generated or uploaded.
EOF
cat "${ARTIFACT_DIR}/SHA256SUMS.txt"
log "AuthorGram two-APK release artifacts are verified"
