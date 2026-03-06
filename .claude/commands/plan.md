# Plan a feature or fix

Analyze the user's request, review the roadmap for related items, and create an implementation plan.

## Steps
1. **Clarify requirements** — Ask questions if the scope is unclear
2. **Review ROADMAP.md** — Check if the request maps to planned features. Look for related roadmap items that can be bundled together (e.g., if user asks about screenshot quality, also consider focused screenshot capture and adaptive quality)
3. **Check ISSUES.md** — Look for known issues related to the change
4. **Explore affected code** — Use Explore subagent to read relevant files
5. **Enter plan mode** — Use `EnterPlanMode` to design the approach
6. **Write plan** covering:
   - Context: what problem are we solving
   - Related roadmap items being addressed
   - Changes: which files, what modifications
   - Documentation: which docs need updating (README, CHANGELOG, ISSUES, ROADMAP)
   - Edge cases: sleep/wake, reconnection, different networks
   - Verification: how to test the changes
7. **Get approval** before exiting plan mode

## Input
$ARGUMENTS
