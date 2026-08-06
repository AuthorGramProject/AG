#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(git rev-parse --show-toplevel)"
WORK_ROOT="${RUNNER_TEMP:?RUNNER_TEMP is required}/authorgram-main-final"
MAIN_DIR="${WORK_ROOT}/main"
PLAY_DIR="${WORK_ROOT}/play"
ARTIFACT_DIR="${WORK_ROOT}/artifacts"
TEST_DIR="${WORK_ROOT}/kdf-test"
MAIN_PACKAGE="${MAIN_PACKAGE:-fork.risin42.nagramx}"
PLAY_PACKAGE="${PLAY_PACKAGE:-toss.authorgram.apk}"
VERSION_NAME="${VERSION_NAME:-12.9.2}"
VERSION_CODE="${VERSION_CODE:-6991}"

log() {
  printf '\n==> %s\n' "$*"
}

fail() {
  printf 'AuthorGram Main final release failed: %s\n' "$*" >&2
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
    git -C "${directory}" fetch --force origin "${branch}"
    if ! git -C "${directory}" rebase "origin/${branch}"; then
      git -C "${directory}" rebase --abort || true
      fail "Unable to rebase ${branch} after a concurrent update"
    fi
  done
  fail "Unable to push finalized ${branch} source"
}

sync_from_dev() {
  local destination="$1"
  local preserve_keystore="${2:-false}"
  local keystore_backup="${WORK_ROOT}/main-release.keystore"

  if [[ "${preserve_keystore}" == "true" ]]; then
    [[ -f "${destination}/TMessagesProj/release.keystore" ]] \
      || fail "Main release.keystore is missing before synchronization"
    cp "${destination}/TMessagesProj/release.keystore" "${keystore_backup}"
  fi

  git -C "${destination}" read-tree --reset -u "${DEV_COMMIT}"
  rm -f "${destination}/local.properties"
  rm -rf "${destination}/.gradle" "${destination}/TMessagesProj/build"

  if [[ "${preserve_keystore}" == "true" ]]; then
    cp "${keystore_backup}" "${destination}/TMessagesProj/release.keystore"
  else
    rm -f "${destination}/TMessagesProj/release.keystore"
  fi

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

verify_main_apk() {
  local apk="$1"
  local certificate_output="$2"
  local aapt="${ANDROID_HOME}/build-tools/36.0.0/aapt"
  local apksigner="${ANDROID_HOME}/build-tools/36.0.0/apksigner"
  local zipalign="${ANDROID_HOME}/build-tools/36.0.0/zipalign"
  local badging package version_name version_code permissions signer_report cert_digest expected_digest

  "${zipalign}" -c -P 16 4 "${apk}"
  badging="$("${aapt}" dump badging "${apk}")"
  package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"
  version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"
  version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n 1)"

  [[ "${package}" == "${MAIN_PACKAGE}" ]] || fail "APK package ${package} != ${MAIN_PACKAGE}"
  [[ "${version_code}" == "${VERSION_CODE}" ]] || fail "APK versionCode ${version_code} != ${VERSION_CODE}"
  [[ "${version_name}" == "${VERSION_NAME}" ]] || fail "APK versionName ${version_name} != ${VERSION_NAME}"
  if grep -q '^application-debuggable' <<<"${badging}"; then
    fail "Release APK is debuggable"
  fi

  permissions="$("${aapt}" dump permissions "${apk}")"
  grep -q "android.permission.REQUEST_INSTALL_PACKAGES" <<<"${permissions}" \
    || fail "Main APK cannot request user-approved APK installation"

  signer_report="$("${apksigner}" verify --verbose --print-certs "${apk}")"
  printf '%s\n' "${signer_report}" | tee "${certificate_output}"
  grep -Eq 'Verified using v2 scheme.*true|Verified using v3 scheme.*true' <<<"${signer_report}" \
    || fail "APK has no verified modern signature scheme"

  cert_digest="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"${signer_report}" | head -n 1 | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
  expected_digest="$(printf '%s' "${AUTHORGRAM_SIGNING_CERT_SHA256:-}" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
  if [[ -n "${expected_digest}" && "${cert_digest}" != "${expected_digest}" ]]; then
    fail "Main APK signing certificate does not match AUTHORGRAM_SIGNING_CERT_SHA256"
  fi

  if unzip -Z1 "${apk}" | grep -Eqi '(^|/)(release\.keystore|[^/]*\.jks|[^/]*\.p12|[^/]*\.pfx)$'; then
    fail "Signing material was packaged into the APK"
  fi
}

log "Prepare isolated Main final-release workspace"
git worktree prune
git config core.fileMode false
git config user.name "AuthorGram Release Bot"
git config user.email "actions@users.noreply.github.com"
git fetch --force --prune origin dev main play-market
git reset --hard origin/dev
git clean -fd
rm -rf "${WORK_ROOT}"
mkdir -p "${ARTIFACT_DIR}" "${TEST_DIR}"

log "Finalize and validate dev source without mutating workflow definitions"
python3 scripts/finalize_authorgram_source.py --role dev --package "${MAIN_PACKAGE}"
python3 scripts/patch_authorgram_popup_bounds.py
python3 scripts/patch_authorgram_play_policy.py
git diff --check
commit_and_push "${ROOT}" dev "[skip ci] Finalize chat UI and Main release source"
DEV_COMMIT="$(git -C "${ROOT}" rev-parse HEAD)"

log "Create isolated Main and Play source worktrees"
git fetch --force origin main play-market
git worktree add --force --detach "${MAIN_DIR}" origin/main >/dev/null
git worktree add --force --detach "${PLAY_DIR}" origin/play-market >/dev/null
for checkout in "${MAIN_DIR}" "${PLAY_DIR}"; do
  git -C "${checkout}" config core.fileMode false
  git -C "${checkout}" config user.name "AuthorGram Release Bot"
  git -C "${checkout}" config user.email "actions@users.noreply.github.com"
done

log "Synchronize finalized source into Main"
sync_from_dev "${MAIN_DIR}" true
python3 "${MAIN_DIR}/scripts/finalize_authorgram_source.py" \
  --role main --package "${MAIN_PACKAGE}"
python3 "${MAIN_DIR}/scripts/patch_authorgram_popup_bounds.py"
commit_and_push "${MAIN_DIR}" main "[skip ci] Synchronize final AuthorGram Main source"

log "Synchronize the shared header and normal-menu fixes into Play without building Play"
sync_from_dev "${PLAY_DIR}" false
python3 "${PLAY_DIR}/scripts/finalize_authorgram_source.py" \
  --role play --package "${PLAY_PACKAGE}"
python3 "${PLAY_DIR}/scripts/patch_authorgram_play_policy.py"
commit_and_push "${PLAY_DIR}" play-market "[skip ci] Synchronize standard chat header into AuthorGram Play"

log "Verify Main and Play application-source parity"
git fetch --force origin main play-market
python3 scripts/authorgram_parity_guard.py \
  --main-ref origin/main \
  --play-ref origin/play-market

MAIN_COMMIT="$(git -C "${MAIN_DIR}" rev-parse HEAD)"
PLAY_COMMIT="$(git -C "${PLAY_DIR}" rev-parse HEAD)"

log "Validate release inputs and deterministic passphrase KDF"
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

log "Build the Main release APK only"
printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > "${MAIN_DIR}/local.properties"
(
  cd "${MAIN_DIR}"
  ./gradlew --no-daemon --stacktrace clean TMessagesProj:assembleRelease
)
MAIN_APK="$(find_arm64_apk "${MAIN_DIR}")"
verify_main_apk "${MAIN_APK}" "${ARTIFACT_DIR}/Main-CERTIFICATE.txt"
mv "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-release-arm64-v8a.apk"

log "Produce Main release metadata"
cat > "${ARTIFACT_DIR}/Main-BUILD.txt" <<EOF
branch=main
commit=${MAIN_COMMIT}
package=${MAIN_PACKAGE}
versionName=${VERSION_NAME}
versionCode=${VERSION_CODE}
canonicalDevCommit=${DEV_COMMIT}
playSourceCommit=${PLAY_COMMIT}
playBuilt=false
EOF
(
  cd "${ARTIFACT_DIR}"
  sha256sum AuthorGram-Main-*.apk > SHA256SUMS.txt
)
cat > "${ARTIFACT_DIR}/RELEASE-SUMMARY.txt" <<EOF
AuthorGram ${VERSION_NAME} final Main release

Main package: ${MAIN_PACKAGE}
Main commit: ${MAIN_COMMIT}
Canonical dev commit: ${DEV_COMMIT}
Play source synchronized at: ${PLAY_COMMIT}
Play APK intentionally not rebuilt.

Verified invariants:
- ChatActivity always uses the ordinary non-centered Telegram header in Main and Play.
- CenterActionBarTitle remains available on non-chat screens.
- The Main-only iOS selected-message preview is fixed outside the actions ScrollView.
- Only the action menu scrolls; normal and iOS message menus can reach the final item.
- The bottom quick-action block matches the menu width.
- The iOS composer restores the send icon whenever entered text owns the slot.
- Main-only iOS UI remains disabled by policy in Play.
- Main APK is release-signed, non-debuggable and arm64-v8a.
- Signing material is absent from the APK.
EOF
cat "${ARTIFACT_DIR}/SHA256SUMS.txt"
log "AuthorGram Main final release artifact is verified"
