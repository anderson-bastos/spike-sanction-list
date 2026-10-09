#!/usr/bin/env python3
"""Read-only architecture review; optional GitHub issue publication."""
import hashlib
import json
import os
import re
from pathlib import Path

import requests
from openai import OpenAI

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "architecture-review-results.json"
EXTENSIONS = {".kt", ".java", ".kts", ".yml", ".yaml", ".properties", ".sql"}
EXCLUDED = {".git", ".gradle", "build", "target", "node_modules", ".idea", "out"}
MAX_FILES = 75
MAX_FILE_CHARS = 10000
MAX_TOTAL_CHARS = 130000
MODEL = os.getenv("OPENAI_MODEL", "gpt-4.1-mini")


def collect_files():
    files = []
    total = 0
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file() or path.suffix not in EXTENSIONS:
            continue
        if any(part in EXCLUDED for part in path.relative_to(ROOT).parts):
            continue
        if len(files) >= MAX_FILES or total >= MAX_TOTAL_CHARS:
            break
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeError, OSError):
            continue
        if not content.strip():
            continue
        content = content[: min(MAX_FILE_CHARS, MAX_TOTAL_CHARS - total)]
        relative = str(path.relative_to(ROOT))
        files.append({"path": relative, "content": content, "lines": content.splitlines()})
        total += len(content)
    return files


def load_instructions():
    path = ROOT / ".github/agents/code-architecture-reviewer.agent.md"
    if path.is_file():
        return path.read_text(encoding="utf-8")[:25000]
    return ("You are a senior architecture reviewer for Kotlin, Java, Spring Boot, "
            "DDD and Hexagonal Architecture. Be evidence-based and conservative.")


def analyze(files):
    inventory = "\n\n".join(
        f"FILE: {f['path']}\n" + "\n".join(
            f"{i}: {line}" for i, line in enumerate(f["lines"], 1)
        ) for f in files
    )
    prompt = (
        "Review ONLY the provided source files. Do not assume unseen behavior. "
        "Report concrete, actionable architecture/performance/reliability/security defects. "
        "Avoid style-only suggestions. Return at most 12 findings. "
        "For each finding, cite an exact file and line present in the input. "
        "Use this JSON schema: {\"findings\": [{\"title\": str, "
        "\"severity\": \"high|medium|low\", \"file\": str, "
        "\"line\": int, \"evidence\": str, \"impact\": str, "
        "\"recommendation\": str, \"tests\": str}]}. "
        "If evidence is insufficient, omit the finding.\n\n" + inventory
    )
    client = OpenAI()
    response = client.chat.completions.create(
        model=MODEL,
        temperature=0,
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": load_instructions()},
            {"role": "user", "content": prompt},
        ],
    )
    result = json.loads(response.choices[0].message.content)
    if not isinstance(result, dict) or not isinstance(result.get("findings"), list):
        raise ValueError("Invalid model response: findings list missing")
    return result["findings"]


def validate(findings, files):
    index = {f["path"]: f for f in files}
    validated = []
    for item in findings:
        if not isinstance(item, dict):
            continue
        try:
            path = item["file"]
            line = int(item["line"])
            severity = item["severity"].lower()
            if path not in index or not (1 <= line <= len(index[path]["lines"])):
                continue
            if severity not in {"high", "medium", "low"}:
                continue
            if not all(isinstance(item.get(k), str) and item[k].strip()
                       for k in ("title", "evidence", "impact", "recommendation", "tests")):
                continue
            snippet = index[path]["lines"][line - 1].strip()
            if not snippet:
                continue
            key = hashlib.sha256(f"{path}:{item['title'].strip().lower()}".encode()).hexdigest()[:16]
            validated.append({**item, "line": line, "severity": severity,
                              "source_line": snippet, "finding_id": key})
        except (KeyError, ValueError, TypeError, AttributeError):
            continue
    return validated[:12]


def publish(findings):
    token = os.environ.get("GH_APP_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not token or not repo or not re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        raise RuntimeError("GH_APP_TOKEN and GITHUB_REPOSITORY are required to publish")
    session = requests.Session()
    session.headers.update({
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    base = f"https://api.github.com/repos/{repo}/issues"
    existing = set()
    page = 1
    while True:
        response = session.get(base, params={"state": "all", "per_page": 100, "page": page}, timeout=30)
        response.raise_for_status()
        batch = response.json()
        for issue in batch:
            if "pull_request" not in issue:
                existing.update(re.findall(r"ARCH-REVIEW-ID:([0-9a-f]{16})", issue.get("body") or ""))
        if len(batch) < 100:
            break
        page += 1
    for f in findings:
        if f["finding_id"] in existing:
            print(f"Skipping duplicate: {f['title']}")
            continue
        body = (f"**Severity:** {f['severity']}\n\n"
                f"**Evidence:** `{f['file']}:{f['line']}`\n\n"
                f"```\n{f['source_line']}\n```\n\n"
                f"{f['evidence']}\n\n**Impact:** {f['impact']}\n\n"
                f"**Recommendation:** {f['recommendation']}\n\n"
                f"**Suggested tests:** {f['tests']}\n\n"
                f"<!-- ARCH-REVIEW-ID:{f['finding_id']} -->")
        response = session.post(base, json={"title": f"[Architecture Review] {f['title'][:180]}", "body": body}, timeout=30)
        response.raise_for_status()
        print("Created issue:", response.json()["html_url"])


def main():
    files = collect_files()
    if not files:
        raise RuntimeError("No source files found")
    print(f"Reviewing {len(files)} files with {MODEL}")
    findings = validate(analyze(files), files)
    REPORT.write_text(json.dumps({"model": MODEL, "files_reviewed": len(files),
                                  "findings": findings}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Validated {len(findings)} findings; report: {REPORT}")
    if os.getenv("PUBLISH_ISSUES", "false").lower() == "true":
        publish(findings)
    else:
        print("Dry run: no issues created")


if __name__ == "__main__":
    main()
