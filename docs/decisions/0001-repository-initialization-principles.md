# 0001. Repository initialization principles

## Status

Accepted

## Context

This repository is being initialized for human and AI-assisted development.

## Decision

- Keep root `CLAUDE.md` short and stable.
- Keep changing context in generated living files under `.claude/context/`.
- Use Git `post-commit` to refresh codebase and recent-change context.
- Do not let hooks create, amend, or stage commits automatically.
- Exclude secrets, generated files, dependencies, and build outputs from routine AI editing.

## Consequences

After commits, context files may be modified in the working tree. Review them like any other generated documentation.
