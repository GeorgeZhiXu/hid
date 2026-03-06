# Project status summary

Provide a concise overview of the project's current state.

## Steps

1. **Recent changes** — Run `git log --oneline -10` to show last 10 commits

2. **Open issues** — Read `ISSUES.md` and list open issues with their status

3. **Test results** — Check if tests were run recently:
   - Unit tests: `./gradlew test` (run if not recent)
   - Check if tablet is connected: `adb devices`
   - Report last E2E result if available

4. **Build status** — Check if APK and Mac binary are up to date:
   - Compare source timestamps vs binary timestamps
   - Report if rebuild is needed

5. **Roadmap progress** — Read `ROADMAP.md` and summarize:
   - What's been implemented recently
   - What's next in near-term

6. **Version info** — Read `app/build.gradle.kts` for current versionName/versionCode

7. **Summary table** format:
   ```
   | Area            | Status |
   |-----------------|--------|
   | Version         | v0.X.0 |
   | Unit tests      | X passed |
   | E2E tests       | X passed, Y failed |
   | Open issues     | X |
   | Last commit     | [message] |
   | Device          | connected / not connected |
   | Mac binary      | up to date / needs rebuild |
   ```

## Scope
$ARGUMENTS
