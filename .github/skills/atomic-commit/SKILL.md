---
name: atomic-commit
description: "Skill to guide atomic, per-file commits with short titles and optional commit bodies. Use when you want every changed file committed separately (one file → one commit)."
argument-hint: "dry-run | auto"
---

# Atomic Commits Skill

**Summary**
- This workspace-scoped skill defines a short, repeatable workflow for committing changes as atomic commits (one file per commit) and producing commit-message guidance (short title < 100 chars plus optional body).

**When to Use**
- Use for change sets that must be split into single-file commits for review or auditing.
- Use when CI or code review policies prefer granular commits.

**Process (step-by-step)**
1. Identify changed files to commit.
2. For each file, create a single commit containing only that file's changes.
3. Provide a concise commit title (imperative, present-tense, < 100 chars).
4. Optionally include a longer commit body separated by a blank line.
5. Repeat until all changed files are committed.

**Decision Points**
- If multiple unrelated logical changes are grouped in one file, consider splitting edits across commits where feasible.
- If a file change is a trivial formatting-only edit, consider grouping or annotating in the commit title (e.g., "format: ...").

**Quality Criteria / Checks**
- Each commit modifies only a single file (verify via `git show --name-only` before pushing).
- Commit title length < 100 chars.
- Commit title is present-tense, imperative (e.g., "Add input validation to parser").
- If used, commit body explains why and any non-obvious details.
 - Commit title and body should use lowercase by default; use capitals only for proper nouns, acronyms, or when clarity requires it.

**Commit Message Guidance**
- Short title (required):  One line, under 100 characters.
- Optional body (recommended for non-trivial changes): separated by a blank line from the title; may include bullet rationale, test notes, and references.

**Lowercase rule**
- Prefer lowercase for both commit titles and bodies unless a proper noun, acronym (e.g., SAP, W3P), or technical identifier requires capitalization. This keeps history consistent and easier to scan.

Examples:
- Title only:
  - "Fix null-pointer in AccountMapper"

- Title + body:
  - "Add request timeout to SOAPConnection

    The connection previously used no timeout and hung intermittently during downstream failures. Add a 30s timeout and log failure details."

**Example prompts to use this skill**
- "Create atomic commits for all modified files, using short titles and optional bodies."
- "Split the current working tree changes into one commit per file, suggest commit titles under 100 chars, and provide optional commit bodies."

**Suggested Next Steps / Related Customizations**
- Add a pre-commit hook to warn when a commit contains >1 file (if policy strictly requires single-file commits).
- Create a companion `*.prompt.md` that runs locally to suggest commit titles and bodies for staged files.

**Notes**
- This skill documents the workflow and example prompts; it does not run `git` commands automatically. Use the prompts to generate commit messages, then apply commits locally (or ask the agent to produce `git` commands to run).

