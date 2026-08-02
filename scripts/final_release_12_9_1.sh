#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(git rev-parse --show-toplevel)"
WORK_ROOT="${RUNNER_TEMP}/authorgram-final"
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

  git -C "${directory}" add -A
  if git -C "${directory}" diff --cached --quiet; then
    printf 'No source changes required for %s.\n' "${branch}"
    return
  fi
  git -C "${directory}" diff --cached --check
  git -C "${directory}" commit -m "${message}"
  git -C "${directory}" push origin "HEAD:${branch}"
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
rm -rf "${WORK_ROOT}"
mkdir -p "${ARTIFACT_DIR}" "${TEST_DIR}"
git config user.name "AuthorGram Release Bot"
git config user.email "actions@users.noreply.github.com"
git fetch --force --prune origin dev main play-market

log "Finalize and validate dev source without destructive checkout operations"
python3 scripts/finalize_authorgram_source.py --role dev --package "${MAIN_PACKAGE}"
git diff --check
commit_and_push "${ROOT}" dev "[skip ci] Align dev source for final AuthorGram release"
DEV_COMMIT="$(git -C "${ROOT}" rev-parse HEAD)"

log "Create isolated Main and Play worktrees"
git worktree add --force --detach "${MAIN_DIR}" origin/main >/dev/null
git worktree add --force --detach "${PLAY_DIR}" origin/play-market >/dev/null
git -C "${MAIN_DIR}" config user.name "AuthorGram Release Bot"
git -C "${MAIN_DIR}" config user.email "actions@users.noreply.github.com"
git -C "${PLAY_DIR}" config user.name "AuthorGram Release Bot"
git -C "${PLAY_DIR}" config user.email "actions@users.noreply.github.com"

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
python3 - "${ROOT}" <<'PY'
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
result = subprocess.run(
    ["git", "diff", "--name-only", "origin/main", "origin/play-market"],
    cwd=root,
    text=True,
    check=True,
    stdout=subprocess.PIPE,
).stdout.splitlines()
allowed_exact = {"gradle.properties", "TMessagesProj/release.keystore"}
unexpected = [
    path for path in result
    if path not in allowed_exact and not path.startswith(".github/")
]
if unexpected:
    raise SystemExit("Unexpected Main/Play source differences: " + ", ".join(unexpected))

main_props = subprocess.run(
    ["git", "show", "origin/main:gradle.properties"],
    cwd=root,
    text=True,
    check=True,
    stdout=subprocess.PIPE,
).stdout
play_props = subprocess.run(
    ["git", "show", "origin/play-market:gradle.properties"],
    cwd=root,
    text=True,
    check=True,
    stdout=subprocess.PIPE,
).stdout
if "APP_PACKAGE=fork.risin42.nagramx" not in main_props:
    raise SystemExit("Main package identity is incorrect")
if "APP_PACKAGE=toss.authorgram.apk" not in play_props:
    raise SystemExit("Play package identity is incorrect")
normalized_main = main_props.replace(
    "APP_PACKAGE=fork.risin42.nagramx", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
)
normalized_play = play_props.replace(
    "APP_PACKAGE=toss.authorgram.apk", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
)
if normalized_main != normalized_play:
    raise SystemExit("gradle.properties differs by more than APP_PACKAGE")
print("Main/Play application source parity passed")
PY

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
cp "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"

log "Build Play release APK and AAB with stable release signing identity"
cp "${MAIN_DIR}/TMessagesProj/release.keystore" "${PLAY_DIR}/TMessagesProj/release.keystore"
printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > "${PLAY_DIR}/local.properties"
(
  cd "${PLAY_DIR}"
  ./gradlew --no-daemon --stacktrace clean \
    TMessagesProj:assembleRelease \
    TMessagesProj:bundleRelease
)
PLAY_APK="$(find_arm64_apk "${PLAY_DIR}")"
verify_apk "${PLAY_APK}" "${PLAY_PACKAGE}" "${ARTIFACT_DIR}/Play-CERTIFICATE.txt"
mapfile -t PLAY_BUNDLES < <(find "${PLAY_DIR}/TMessagesProj/build/outputs/bundle/release" \
  -maxdepth 1 -type f -name '*.aab' -print)
[[ "${#PLAY_BUNDLES[@]}" -eq 1 ]] \
  || fail "Expected exactly one Play AAB, found ${#PLAY_BUNDLES[@]}"
PLAY_AAB="${PLAY_BUNDLES[0]}"
jarsigner -verify "${PLAY_AAB}"
keytool -printcert -jarfile "${PLAY_AAB}" > "${ARTIFACT_DIR}/Play-AAB-CERTIFICATE.txt"
cp "${PLAY_APK}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release-arm64-v8a.apk"
cp "${PLAY_AAB}" "${ARTIFACT_DIR}/AuthorGram-Play-v${VERSION_NAME}-release.aab"
rm -f "${PLAY_DIR}/TMessagesProj/release.keystore"

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
  sha256sum AuthorGram-*.apk AuthorGram-*.aab > SHA256SUMS.txt
)
cat > "${ARTIFACT_DIR}/RELEASE-SUMMARY.txt" <<EOF
AuthorGram ${VERSION_NAME} final verified release

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
- Legacy visible Nagram/Nekogram branding is rejected by source validation.
- Release APKs are signed, non-debuggable, minified and shrink resources.
- Play APK and AAB use the stable existing release signing identity.
- Signing material is not included in APK artifacts or committed to Play source.
- Deterministic AuthorGram passphrase KDF self-test passed.
EOF
cat "${ARTIFACT_DIR}/SHA256SUMS.txt"
log "AuthorGram final release artifacts are verified"
