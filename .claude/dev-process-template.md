# Reusable Development Process Template

Copy this to any new project as `CLAUDE.md` and customize.

---

# [Project Name] — Claude Code Instructions

## Project Overview
[Brief description of what this project does]

## Architecture
[Key directories and their roles]

## Development Process

### Phase 1: Requirement Analysis
- Clarify the user's goal and constraints
- Ask questions if the scope is ambiguous
- Identify affected components

### Phase 2: Design & Planning
- Use `EnterPlanMode` for non-trivial changes (>3 files or architectural decisions)
- Write plan: context, changes, edge cases, verification
- Get user approval before implementing

### Phase 3: Implementation & Documentation
- Read existing code before modifying
- Keep changes minimal — don't refactor unrelated code
- Update README/docs if user-facing behavior changes
- Extract testable pure functions where possible

### Phase 4: Testing
- Run unit tests: `[test command]`
- Run integration/E2E tests: `[e2e command]`
- Verify edge cases specific to the change

### Phase 5: Commit, Push & Publish
- Commit with descriptive WHY message (not just WHAT)
- Push to appropriate branch
- Update releases/deployments if applicable

## Build Commands
```bash
# Build
[build command]

# Test
[test command]

# E2E
[e2e command]
```

## Key Technical Details
[Important architectural decisions, protocols, gotchas]

## Common Pitfalls
[Things that have broken before, known quirks]

## Slash Commands
Copy `.claude/commands/` to get:
- `/plan [feature]` — Design and plan a feature
- `/fix [bug description]` — Diagnose and fix a bug
- `/test [scope]` — Run tests
- `/release [version]` — Full release pipeline
- `/explore [area]` — Deep-dive into code
