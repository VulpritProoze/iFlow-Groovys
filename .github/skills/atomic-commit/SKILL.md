---
name: atomic-commit
description: "Skill to guide atomic, per-file commits with short titles and optional commit bodies. Use when you want every changed file committed separately (one file → one commit)."
argument-hint: "dry-run | auto"
---

# Atomic commit

Summary
- One-commit-per-file workflow and commit-message guidance (title <100 chars, optional body).

When
- Use when changes must be split into granular commits for review or auditing.

Steps
1. List changed files.
2. For each file, stage only that file and commit it.
3. Use an imperative, present-tense title (<100 chars) and optional body.

Decisions
- If one file contains multiple logical changes, split edits where possible.
- Tag trivial formatting edits (e.g., "format: ...").

Checks
- Commit contains only one file (`git show --name-only`).
- Title <100 chars, prefer lowercase except for proper nouns/acronyms.

Commit message style
- Title: one line, under 100 characters.
- Body: optional, separated by a blank line; explain why, include test notes or references.

Examples
- Title only: "fix null-pointer in accountmapper"
- With body: "add request timeout to soapconnection

  add 30s timeout to avoid hangs; log failures"

Usage prompts
- "create atomic commits for all modified files (dry-run|auto)"
- "split staged changes into one commit per file and suggest titles"

Notes
- `dry-run` will show possible commits; `auto` will auto commit files without confirmation; never push

