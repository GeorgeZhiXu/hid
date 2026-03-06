# Build, test, commit, and publish a release

Complete the release pipeline: build → test → commit → push → GitHub release.

## Steps

1. **Build** Android APK and Mac binary:
   ```bash
   ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" ./gradlew assembleDebug
   cd mac && swiftc -framework IOBluetooth -framework Foundation -framework ImageIO screenshot-server.swift -o screenshot-server && cd ..
   ```

2. **Run unit tests**:
   ```bash
   ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" ./gradlew test
   ```

3. **Bump version** in `app/build.gradle.kts` if needed (versionCode + versionName)

4. **Commit** all changes with descriptive message

5. **Push** to main

6. **Update GitHub release**:
   ```bash
   gh release delete vX.Y.Z --yes 2>/dev/null
   gh release create vX.Y.Z \
     app/build/outputs/apk/debug/app-debug.apk \
     mac/screenshot-server#screenshot-server-macos-arm64 \
     --title "vX.Y.Z" \
     --notes "Release notes here"
   ```

## Version
$ARGUMENTS
