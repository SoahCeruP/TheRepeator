# Contributing to TheRepeator

Thank you for considering contributing to TheRepeator! This app is open-source and respects user freedom and privacy.

## Building from Source

To build the APK locally:

1. Clone the repository:
   ```bash
   git clone https://github.com/SoahCeruP/TheRepeator.git
   cd TheRepeator
   ```

2. Build the Debug APK using Gradle:
   ```bash
   ./gradlew app:assembleDebug
   ```

3. Build the Release APK:
   ```bash
   ./gradlew app:assembleRelease
   ```

## Code Guidelines

- **Kotlin & Jetpack Compose**: Write idiomatic Kotlin and use modern Compose UI practices.
- **Dependencies**: Keep all dependencies Free and Open Source (FOSS). Do not add closed-source SDKs, tracking libraries, or proprietary Google Play services dependencies.

## F-Droid Metadata & Releases

App metadata for F-Droid is located in Fastlane format under:
`fastlane/metadata/android/en-US/`

- `title.txt`: App name
- `short_description.txt`: Brief summary (<= 80 chars)
- `full_description.txt`: Full app overview
- `changelogs/`: Changelog by version code

When releasing a new version:
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a new changelog file in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Tag the commit (e.g. `v1.0.1`) so F-Droid's `AutoUpdateMode` automatically picks up the new tag.
