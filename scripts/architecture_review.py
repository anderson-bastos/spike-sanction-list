#!/usr/bin/env python3
"""Architecture Reviewer — runs the code-architecture-reviewer agent over the repo.

Invoked by .github/workflows/architecture-review.yml (manual workflow_dispatch).

It loads the agent persona from .github/agents/code-architecture-reviewer.agent.md,
gathers repository context (tree + the most recent diff), asks an OpenAI model to
produce an architecture review following that persona, prints the review to the
Actions log, and — when PUBLISH_ISSUES=true — opens a single GitHub issue with the
review using the GitHub App token.

Environment:
  OPENAI_API_KEY   (required) OpenAI API key.
  OPENAI_MODEL     (optional) model name; default "gpt-4o-mini".
  GH_APP_TOKEN     (optional) GitHub App installation token (needs issues:write)
                   — required only when PUBLISH_ISSUES=true.
  GITHUB_REPOSITORY(provided by Actions) "owner/repo".
  PUBLISH_ISSUES   (optional) "true" to open an issue; anything else = log only.

Exit codes: 0 on success (review produced), 2 on configuration error.
"""
from __future__ import annotations

import os
import subprocess
import sys


AGENT_FILE = ".github/agents/code-architecture-reviewer.agent.md"
DEFAULT_MODEL = "gpt-4o-mini"
MAX_DIFF_CHARS = 60_000  # keep the prompt within sane bounds


def fail(msg: str, code: int = 2) -> "None":
    print(f"::error::{msg}")
    sys.exit(code)


def run(cmd: list[str]) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout
    except Exception as exc:  # pragma: no cover - defensive
        print(f"::warning::command failed {cmd}: {exc}")
        return ""


def read_agent_persona() -> str:
    if not os.path.exists(AGENT_FILE):
        fail(f"agent persona file not found: {AGENT_FILE}")
    with open(AGENT_FILE, encoding="utf-8") as fh:
        return fh.read()


def gather_context() -> str:
    # Repo tree (tracked files only) + the most recent commit diff. The workflow is
    # manual (workflow_dispatch), so there is no PR diff; HEAD~1..HEAD is the most
    # recent change set. Falls back to the full HEAD if there is only one commit.
    tree = run(["git", "ls-files"])
    log = run(["git", "log", "--oneline", "-10"])
    diff = run(["git", "diff", "HEAD~1", "HEAD"]) or run(["git", "show", "HEAD"])
    if len(diff) > MAX_DIFF_CHARS:
        diff = diff[:MAX_DIFF_CHARS] + "\n... [diff truncated] ..."
    return (
        "## Repository file tree (tracked)\n" + tree
        + "\n## Recent commits\n" + log
        + "\n## Most recent diff (HEAD~1..HEAD)\n" + diff
    )


def build_prompt(persona: str, context: str) -> list[dict]:
    system = (
        persona
        + "\n\n---\nYou are running non-interactively in CI. Produce a concise, "
        "actionable architecture review in Markdown, following the checklist and "
        "report format in the persona above. If you cannot see a violation, say so "
        "explicitly rather than inventing one."
    )
    user = (
        "Review the current state of this repository and its most recent change set. "
        "Repository context follows.\n\n" + context
    )
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]


def call_openai(messages: list[dict]) -> str:
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        fail("OPENAI_API_KEY is not set")
    model = os.environ.get("OPENAI_MODEL", DEFAULT_MODEL)
    try:
        from openai import OpenAI
    except ImportError:
        fail("the 'openai' package is not installed (pip install openai)")
    client = OpenAI(api_key=api_key)
    resp = client.chat.completions.create(
        model=model,
        messages=messages,
        temperature=0.2,
    )
    return resp.choices[0].message.content or "(empty review)"


def publish_issue(title: str, body: str) -> "None":
    token = os.environ.get("GH_APP_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not token or not repo:
        print("::warning::PUBLISH_ISSUES requested but GH_APP_TOKEN/GITHUB_REPOSITORY missing; skipping issue creation.")
        return
    import requests

    url = f"https://api.github.com/repos/{repo}/issues"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    r = requests.post(url, headers=headers, json={"title": title, "body": body, "labels": ["architecture-review"]})
    if r.status_code >= 300:
        print(f"::warning::failed to create issue ({r.status_code}): {r.text[:300]}")
    else:
        print(f"::notice::opened issue: {r.json().get('html_url')}")


def main() -> "None":
    persona = read_agent_persona()
    context = gather_context()
    review = call_openai(build_prompt(persona, context))

    print("\n===== ARCHITECTURE REVIEW =====\n")
    print(review)
    print("\n===== END REVIEW =====\n")

    # Also surface it in the Actions job summary when available.
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("# Architecture Review\n\n" + review + "\n")

    if os.environ.get("PUBLISH_ISSUES", "").lower() == "true":
        publish_issue("Architecture review", review)
    else:
        print("::notice::PUBLISH_ISSUES not true — review logged only, no issue created.")


if __name__ == "__main__":
    main()
