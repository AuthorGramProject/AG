#!/usr/bin/env python3
"""Remove obsolete Designers workflows and stale AuthorGram Actions runs."""

from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CUTOFF_TITLE = "Update/telegram 12.9.0 20260718 212239"
# The actual Telegram 12.9 merge commit was created at this time. The title run
# may already have been deleted, so this timestamp is the deterministic fallback.
FALLBACK_CUTOFF_ISO = "2026-07-19T22:45:50Z"
DESIGNER_PATTERN = re.compile(r"\bdesigners?\b", re.IGNORECASE)
BRANCHES = ("dev", "main", "play-market")


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


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


def workflow_title(source: str) -> str:
    match = re.search(r"^name:\s*(.+?)\s*$", source, re.MULTILINE)
    if match is None:
        return ""
    return match.group(1).strip().strip("'\"")


def remove_designer_workflow_files(api: GitHubApi) -> int:
    deleted = 0
    quoted_directory = urllib.parse.quote(".github/workflows", safe="/")
    for branch in BRANCHES:
        try:
            entries = api.request(
                "GET",
                f"/repos/{api.repository}/contents/{quoted_directory}?ref={urllib.parse.quote(branch)}",
            )
        except RuntimeError as error:
            if "HTTP 404" in str(error):
                print(f"No workflow directory on {branch}.")
                continue
            raise
        if not isinstance(entries, list):
            continue
        for entry in entries:
            name = str(entry.get("name", ""))
            path = str(entry.get("path", ""))
            if entry.get("type") != "file" or not re.search(r"\.ya?ml$", name, re.I):
                continue

            is_designer = DESIGNER_PATTERN.search(name) is not None
            if not is_designer:
                quoted_path = urllib.parse.quote(path, safe="/")
                file_data = api.request(
                    "GET",
                    f"/repos/{api.repository}/contents/{quoted_path}?ref={urllib.parse.quote(branch)}",
                )
                encoded = str(file_data.get("content", "")).replace("\n", "")
                source = base64.b64decode(encoded).decode("utf-8", "replace")
                is_designer = DESIGNER_PATTERN.search(workflow_title(source)) is not None

            if not is_designer:
                continue

            quoted_path = urllib.parse.quote(path, safe="/")
            api.request(
                "DELETE",
                f"/repos/{api.repository}/contents/{quoted_path}",
                {
                    "message": f"[skip ci] Remove obsolete Designers workflow from {branch}",
                    "sha": entry["sha"],
                    "branch": branch,
                },
                expected=(200,),
            )
            deleted += 1
            print(f"Deleted obsolete workflow {path} from {branch}.")
    return deleted


def run_label(run: dict[str, Any]) -> str:
    return " ".join(
        str(value)
        for value in (run.get("name"), run.get("display_title"))
        if value
    )


def resolve_cutoff(runs: list[dict[str, Any]]) -> tuple[datetime, str]:
    matches: list[dict[str, Any]] = []
    for run in runs:
        values = (
            run.get("display_title"),
            run.get("name"),
            (run.get("head_commit") or {}).get("message"),
        )
        if CUTOFF_TITLE in values:
            matches.append(run)
    if matches:
        matches.sort(key=lambda item: parse_time(item["created_at"]), reverse=True)
        value = matches[0]["created_at"]
        return parse_time(value), value
    return parse_time(FALLBACK_CUTOFF_ISO), FALLBACK_CUTOFF_ISO


def clean_runs(api: GitHubApi, current_run_id: int) -> dict[str, int]:
    runs = api.get_all_runs()
    cutoff_time, cutoff_iso = resolve_cutoff(runs)
    counts = {"historical": 0, "designer": 0, "unsuccessful": 0}

    for run in runs:
        run_id = int(run["id"])
        if run_id == current_run_id:
            continue
        historical = parse_time(run["created_at"]) <= cutoff_time
        designer = DESIGNER_PATTERN.search(run_label(run)) is not None
        unsuccessful = run.get("conclusion") in {"failure", "cancelled"}
        if not historical and not designer and not unsuccessful:
            continue
        api.request(
            "DELETE",
            f"/repos/{api.repository}/actions/runs/{run_id}",
            expected=(204,),
        )
        if historical:
            counts["historical"] += 1
        elif designer:
            counts["designer"] += 1
        else:
            counts["unsuccessful"] += 1

    remaining = api.get_all_runs()
    historical_left = [
        run
        for run in remaining
        if int(run["id"]) != current_run_id
        and parse_time(run["created_at"]) <= cutoff_time
    ]
    designer_left = [
        run
        for run in remaining
        if int(run["id"]) != current_run_id
        and DESIGNER_PATTERN.search(run_label(run)) is not None
    ]
    unsuccessful_left = [
        run
        for run in remaining
        if int(run["id"]) != current_run_id
        and run.get("conclusion") in {"failure", "cancelled"}
    ]
    if historical_left or designer_left or unsuccessful_left:
        raise RuntimeError(
            "Workflow cleanup incomplete: "
            f"historical={len(historical_left)}, "
            f"designers={len(designer_left)}, "
            f"failed_or_cancelled={len(unsuccessful_left)}"
        )

    print(f"Cleanup cutoff: {cutoff_iso} — {CUTOFF_TITLE}")
    return counts


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        print("GitHub Actions cleanup skipped outside GitHub Actions.")
        return
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    current_run = os.environ.get("GITHUB_RUN_ID", "")
    if not repository or not current_run.isdigit():
        raise RuntimeError("GITHUB_REPOSITORY or GITHUB_RUN_ID is unavailable")

    api = GitHubApi(repository, read_git_authorization())
    designer_files = remove_designer_workflow_files(api)
    counts = clean_runs(api, int(current_run))
    print(f"Deleted {designer_files} Designers workflow file(s).")
    print(f"Deleted {counts['historical']} historical run(s).")
    print(f"Deleted {counts['designer']} newer Designers run(s).")
    print(f"Deleted {counts['unsuccessful']} newer failed/cancelled run(s).")


if __name__ == "__main__":
    main()
