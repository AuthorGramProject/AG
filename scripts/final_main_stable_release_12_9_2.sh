#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(git rev-parse --show-toplevel)"
WORK_ROOT="${RUNNER_TEMP:?RUNNER_TEMP is required}/authorgram-main-stable"
MAIN_DIR="${WORK_ROOT}/main"
ARTIFACT_DIR="${WORK_ROOT}/artifacts"
TEST_DIR="${WORK_ROOT}/kdf-test"
MAIN_PACKAGE="${MAIN_PACKAGE:-fork.risin42.nagramx}"
VERSION_NAME="${VERSION_NAME:-12.9.2}"
VERSION_CODE="${VERSION_CODE:-6991}"

log() { printf '\n==> %s\n' "$*"; }
fail() { printf 'AuthorGram stable Main release failed: %s\n' "$*" >&2; exit 1; }

cleanup() {
  git -C "${ROOT}" worktree remove --force "${MAIN_DIR}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

commit_and_push() {
  local directory="$1"
  local branch="$2"
  local message="$3"
  git -C "${directory}" config core.fileMode false
  git -C "${directory}" add -A
  if git -C "${directory}" diff --cached --quiet; then
    printf 'No source changes required for %s.\n' "${branch}"
    return
  fi
  git -C "${directory}" diff --cached --check
  git -C "${directory}" commit -m "${message}"
  git -C "${directory}" push origin "HEAD:${branch}"
}

commit_local_dev_snapshot() {
  # AUTHORGRAM_NO_SELF_TRIGGER_DEV_PUSH
  # The release PR is based on dev. Pushing dev from inside its own workflow
  # retriggers pull_request and cancels the running build via concurrency.
  # Keep the finalized dev snapshot local to this runner; only Main is pushed.
  git -C "${ROOT}" config core.fileMode false
  git -C "${ROOT}" add -A
  if git -C "${ROOT}" diff --cached --quiet; then
    printf 'No local dev snapshot changes required.\n'
    return
  fi
  git -C "${ROOT}" diff --cached --check
  git -C "${ROOT}" commit -m "[skip ci] Local stable Main source snapshot"
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
  local zipalign="${ANDROID_HOME}/build-tools/36.0.0/zipalign"
  local badging package version_name version_code signer cert_digest expected_digest

  "${zipalign}" -c -P 16 4 "${apk}"
  badging="$("${aapt}" dump badging "${apk}")"
  package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n1)"
  version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n1)"
  version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"${badging}" | head -n1)"

  [[ "${package}" == "${MAIN_PACKAGE}" ]] || fail "APK package ${package} != ${MAIN_PACKAGE}"
  [[ "${version_code}" == "${VERSION_CODE}" ]] || fail "versionCode ${version_code} != ${VERSION_CODE}"
  [[ "${version_name}" == "${VERSION_NAME}" ]] || fail "versionName ${version_name} != ${VERSION_NAME}"
  ! grep -q '^application-debuggable' <<<"${badging}" || fail "Release APK is debuggable"

  grep -q "android.permission.REQUEST_INSTALL_PACKAGES" < <("${aapt}" dump permissions "${apk}") \
    || fail "Main APK lost REQUEST_INSTALL_PACKAGES"

  signer="$("${apksigner}" verify --verbose --print-certs "${apk}")"
  printf '%s\n' "${signer}" | tee "${certificate_output}"
  grep -Eq 'Verified using v2 scheme.*true|Verified using v3 scheme.*true' <<<"${signer}" \
    || fail "APK has no verified modern signature"

  cert_digest="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"${signer}" | head -n1 | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
  expected_digest="$(printf '%s' "${AUTHORGRAM_SIGNING_CERT_SHA256:-}" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
  if [[ -n "${expected_digest}" && "${cert_digest}" != "${expected_digest}" ]]; then
    fail "Signing certificate does not match AUTHORGRAM_SIGNING_CERT_SHA256"
  fi

  if unzip -Z1 "${apk}" | grep -Eqi '(^|/)(release\.keystore|[^/]*\.jks|[^/]*\.p12|[^/]*\.pfx)$'; then
    fail "Signing material was packaged into APK"
  fi
}

log "Prepare canonical dev source"
git worktree prune
git config core.fileMode false
git config user.name "AuthorGram Release Bot"
git config user.email "actions@users.noreply.github.com"
git fetch --force --prune origin dev main
git reset --hard origin/dev
git clean -fd
rm -rf "${WORK_ROOT}"
mkdir -p "${ARTIFACT_DIR}" "${TEST_DIR}"

log "Pre-apply legacy/scope scan"
python3 scripts/patch_authorgram_chat_scope_safety.py --mode pre-apply

log "Pre-apply iOS input geometry scan"
python3 scripts/patch_authorgram_ios_input_geometry.py --mode pre-apply

log "Finalize shared source, then apply the canonical Main stability pass"
python3 scripts/finalize_authorgram_source.py --role dev --package "${MAIN_PACKAGE}"
python3 scripts/patch_authorgram_popup_bounds.py
python3 scripts/patch_authorgram_main_stability.py
python3 scripts/patch_authorgram_ios_input_geometry.py --mode apply
python3 scripts/patch_authorgram_chat_scope_safety.py --mode validate
python3 scripts/patch_authorgram_main_stability.py
python3 scripts/patch_authorgram_ios_input_geometry.py --mode validate
git diff --check
commit_local_dev_snapshot
DEV_COMMIT="$(git -C "${ROOT}" rev-parse HEAD)"

log "Create isolated Main worktree without touching Play"
git fetch --force origin main
git worktree add --force --detach "${MAIN_DIR}" origin/main >/dev/null
git -C "${MAIN_DIR}" config core.fileMode false
git -C "${MAIN_DIR}" config user.name "AuthorGram Release Bot"
git -C "${MAIN_DIR}" config user.email "actions@users.noreply.github.com"

[[ -f "${MAIN_DIR}/TMessagesProj/release.keystore" ]] \
  || fail "Main release.keystore is missing"
cp "${MAIN_DIR}/TMessagesProj/release.keystore" "${WORK_ROOT}/main-release.keystore"

git -C "${MAIN_DIR}" read-tree --reset -u "${DEV_COMMIT}"
cp "${WORK_ROOT}/main-release.keystore" "${MAIN_DIR}/TMessagesProj/release.keystore"
rm -f "${MAIN_DIR}/local.properties"
rm -rf "${MAIN_DIR}/.gradle" "${MAIN_DIR}/TMessagesProj/build"
git -C "${MAIN_DIR}" submodule sync --recursive
git -C "${MAIN_DIR}" submodule update --init --depth 1 --jobs 3

log "Finalize and validate isolated Main source"
python3 "${MAIN_DIR}/scripts/finalize_authorgram_source.py" --role main --package "${MAIN_PACKAGE}"
(
  cd "${MAIN_DIR}"
  python3 scripts/patch_authorgram_popup_bounds.py
  python3 scripts/patch_authorgram_main_stability.py
  python3 scripts/patch_authorgram_ios_input_geometry.py --mode apply
  python3 scripts/patch_authorgram_chat_scope_safety.py --mode validate
  python3 scripts/patch_authorgram_main_stability.py
  python3 scripts/patch_authorgram_ios_input_geometry.py --mode validate
  git diff --check
)
commit_and_push "${MAIN_DIR}" main "[skip ci] Synchronize stable AuthorGram Main source"
MAIN_COMMIT="$(git -C "${MAIN_DIR}" rev-parse HEAD)"

log "Validate release inputs and passphrase KDF"
[[ -n "${LOCAL_PROPERTIES:-}" ]] || fail "LOCAL_PROPERTIES secret is missing"
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' "${MAIN_DIR}/gradle.properties")" == "${VERSION_NAME}" ]] \
  || fail "Main versionName mismatch"
[[ "$(sed -n 's/^APP_VERSION_CODE=//p' "${MAIN_DIR}/gradle.properties")" == "${VERSION_CODE}" ]] \
  || fail "Main versionCode mismatch"

javac -encoding UTF-8 -d "${TEST_DIR}" \
  "${MAIN_DIR}/TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPassphraseKdf.java" \
  "${MAIN_DIR}/scripts/java/org/telegram/messenger/authorgram/AuthorGramPassphraseKdfSelfTest.java"
java -cp "${TEST_DIR}" org.telegram.messenger.authorgram.AuthorGramPassphraseKdfSelfTest

log "Build one arm64-v8a Main release APK"
printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > "${MAIN_DIR}/local.properties"
(
  cd "${MAIN_DIR}"
  NATIVE_TARGET=arm64-v8a ./gradlew --no-daemon --stacktrace clean TMessagesProj:assembleRelease
)

MAIN_APK="$(find_arm64_apk "${MAIN_DIR}")"
verify_apk "${MAIN_APK}" "${ARTIFACT_DIR}/Main-CERTIFICATE.txt"
mv "${MAIN_APK}" "${ARTIFACT_DIR}/AuthorGram-Main-v${VERSION_NAME}-stable-release-arm64-v8a.apk"

cat > "${ARTIFACT_DIR}/Main-BUILD.txt" <<EOF
branch=main
commit=${MAIN_COMMIT}
package=${MAIN_PACKAGE}
versionName=${VERSION_NAME}
versionCode=${VERSION_CODE}
canonicalDevCommit=${DEV_COMMIT}
abi=arm64-v8a
playTouched=false
devPushed=false
EOF

cat > "${ARTIFACT_DIR}/RELEASE-SUMMARY.txt" <<EOF
AuthorGram ${VERSION_NAME} stable Main release

Main package: ${MAIN_PACKAGE}
Main commit: ${MAIN_COMMIT}
Canonical local dev snapshot: ${DEV_COMMIT}
Play branch/build: untouched
Dev branch push during release: forbidden

Stability invariants:
- iOS input maintenance is a strict no-op while the iOS input feature is disabled.
- stale delayed composer callbacks are cancelled before lifecycle/style exits.
- empty and non-empty Main iOS composer states share one vertical baseline; stale measurement translation cannot leave the input shifted.
- side-bubble bounds are recalculated after iOS composer layout stabilization.
- selected-message preview is the native ChatMessageCell rendering only.
- no duplicate synthetic sender name or second synthetic bubble is drawn.
- selected-message preview always stays above the action card.
- the Main-only quick-action footer is capped at 44dp.
- classic/non-iOS footer behavior is not re-parented by the Main iOS flow.
- release is signed, non-debuggable, arm64-v8a only.
EOF

(
  cd "${ARTIFACT_DIR}"
  sha256sum AuthorGram-Main-*.apk > SHA256SUMS.txt
)
cat "${ARTIFACT_DIR}/SHA256SUMS.txt"
log "Stable Main release artifact verified"
