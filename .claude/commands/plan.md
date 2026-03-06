# Plan a feature or fix

Analyze the user's request and create an implementation plan.

## Steps
1. **Clarify requirements** — Ask questions if the scope is unclear
2. **Explore affected code** — Use Explore subagent to read relevant files
3. **Enter plan mode** — Use `EnterPlanMode` to design the approach
4. **Write plan** covering:
   - Context: what problem are we solving
   - Changes: which files, what modifications
   - Edge cases: sleep/wake, reconnection, different networks
   - Verification: how to test the changes
5. **Get approval** before exiting plan mode

## Input
$ARGUMENTS
