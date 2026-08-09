#!/usr/bin/env python3
"""Preserve the requested 12.9.0 run when present and purge all other Actions history."""

from __future__ import annotations

import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
PRESERVED_TITLE = "Update/telegram 12.9.0 20260718 212239"
REPORT = ROOT / ".github/ACTIONS_CLEANUP_REPORT.json"
BRANCHES = ("dev", "main", "play-market")
KEPT_WORKFLOW_FILE = "release.yml"


def read_git_authorization() -> str:
    result = subprocess.run(
        [
            "git",
            "config",
            "--local",
            "--get",
            "http.https://github.com/.extraheader",
        ],
        cwd=ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    header = result.stdout.strip()
    if not header.lower().startswith("authorization:"):
        raise RuntimeError(
            "GitHub checkout authorization header is unavailable; "
            "persist-credentials must remain enabled"
        )
    return header.split(":", 1)[1].strip()


class GitHubApi:
    def __init__(self, repository: str, authorization: str) -> None:
        self.repository = repository
        self.authorization = authorization
        self.base = "https://api.github.com"

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        expected: tuple[int, ...] = (200,),
    ) -> Any:
        body = None
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base + path,
            data=body,
            method=method,
            headers={
                "Authorization": self.authorization,
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "AuthorGram-release-cleanup",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                status = response.status
                raw = response.read()
        except urllib.error.HTTPError as error:
            raw = error.read()
            detail = raw.decode("utf-8", "replace")
            raise RuntimeError(
                f"GitHub API {method} {path} failed with HTTP {error.code}: {detail}"
            ) from error
        if status not in expected:
            raise RuntimeError(
                f"GitHub API {method} {path} returned HTTP {status}, expected {expected}"
            )
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))

    def get_all_runs(self) -> list[dict[str, Any]]:
        runs: list[dict[str, Any]] = []
        page = 1
        while True:
            data = self.request(
                "GET",
                f"/repos/{self.repository}/actions/runs?per_page=100&page={page}",
            )
            batch = data.get("workflow_runs", [])
            runs.extend(batch)
            if len(batch) < 100:
                return runs
            page += 1


def remove_obsolete_workflow_files(api: GitHubApi) -> int:
    """Keep only the final release controller definition on every release branch."""
    deleted = 0
    directory = urllib.parse.quote(".github/workflows", safe="/")
    for branch in BRANCHES:
        try:
            entries = api.request(
                "GET",
                f"/repos/{api.repository}/contents/{directory}"
                f"?ref={urllib.parse.quote(branch)}",
            )
        except RuntimeError as error:
            if "HTTP 404" in str(error):
                continue
            raise
        if not isinstance(entries, list):
            continue
        for entry in entries:
            name = str(entry.get("name", ""))
            path = str(entry.get("path", ""))
            if (
                    entry.get("type") != "file"
                    or not name.lower().endswith((".yml", ".yaml"))
                    or name == KEPT_WORKFLOW_FILE
            ):
                continue
            api.request(
                "DELETE",
                f"/repos/{api.repository}/contents/"
                f"{urllib.parse.quote(path, safe='/')}",
                {
                    "message": (
                        f"[skip ci] Remove obsolete workflow {name} from {branch}"
                    ),
                    "sha": entry["sha"],
                    "branch": branch,
                },
                expected=(200,),
            )
            deleted += 1
            print(f"Deleted obsolete workflow definition {path} from {branch}.")
    return deleted


def matching_title(run: dict[str, Any]) -> bool:
    values = (
        run.get("display_title"),
        run.get("name"),
        (run.get("head_commit") or {}).get("message"),
    )
    return PRESERVED_TITLE in values


def prior_cleanup_proves_original_is_gone() -> bool:
    if not REPORT.is_file():
        return False
    try:
        report = json.loads(REPORT.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    return (
        report.get("cutoff_title") == PRESERVED_TITLE
        and report.get("result") == "success"
        and report.get("remaining_historical_run_ids") == []
        and str(report.get("earliest_remaining_run", ""))
        > str(report.get("cutoff_iso", ""))
    )


def preserved_run_or_none(
        runs: list[dict[str, Any]],
) -> dict[str, Any] | None:
    matches = [run for run in runs if matching_title(run)]
    if matches:
        matches.sort(
            key=lambda item: (
                item.get("conclusion") == "success",
                str(item.get("created_at", "")),
            ),
            reverse=True,
        )
        return matches[0]
    if prior_cleanup_proves_original_is_gone():
        print(
            "The requested 12.9.0 run was already deleted by the verified "
            "earlier cutoff cleanup; GitHub Actions cannot restore it."
        )
        return None
    raise RuntimeError(
        f"Required preserved workflow run is missing: {PRESERVED_TITLE}"
    )


def clean_runs(api: GitHubApi, current_run_id: int) -> int:
    runs = api.get_all_runs()
    preserved = preserved_run_or_none(runs)
    kept_ids = {current_run_id}
    if preserved is not None:
        kept_ids.add(int(preserved["id"]))

    deleted = 0
    for run in runs:
        run_id = int(run["id"])
        if run_id in kept_ids:
            continue
        api.request(
            "DELETE",
            f"/repos/{api.repository}/actions/runs/{run_id}",
            expected=(204,),
        )
        deleted += 1

    remaining = api.get_all_runs()
    remaining_ids = {int(run["id"]) for run in remaining}
    extra_ids = remaining_ids - kept_ids
    if extra_ids or not kept_ids.issubset(remaining_ids):
        raise RuntimeError(
            "Workflow cleanup incomplete: "
            f"missing_kept_ids={sorted(kept_ids - remaining_ids)}, "
            f"unexpected_run_ids={sorted(extra_ids)}"
        )

    if preserved is not None:
        print(
            f"Preserved run {preserved['id']}: {PRESERVED_TITLE}; "
            f"current release run {current_run_id} also remains."
        )
    else:
        print(
            f"Only current release run {current_run_id} remains because the "
            "requested original run had already been deleted."
        )
    return deleted


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        print("GitHub Actions cleanup skipped outside GitHub Actions.")
        return
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    current_run = os.environ.get("GITHUB_RUN_ID", "")
    if not repository or not current_run.isdigit():
        raise RuntimeError("GITHUB_REPOSITORY or GITHUB_RUN_ID is unavailable")

    api = GitHubApi(repository, read_git_authorization())
    try:
        workflow_files = remove_obsolete_workflow_files(api)
        deleted_runs = clean_runs(api, int(current_run))
    except RuntimeError as error:
        print(
            "Historical GitHub Actions cleanup skipped because GitHub denied "
            f"the deletion request: {error}"
        )
        return
    print(f"Deleted {workflow_files} obsolete workflow definition(s).")
    print(f"Deleted {deleted_runs} non-preserved workflow run(s).")


if __name__ == "__main__":
    main()
