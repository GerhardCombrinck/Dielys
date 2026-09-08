# android/AGENTS.md

Kotlin + Compose + Room, package `za.co.wonderlys`. Layout and rules: see E1/E2 in
`../docs/CODE_STANDARD.md`, and `data/sync/AGENTS.md` before touching sync code.

## Bootstrapping the Gradle wrapper

This scaffold does not commit `gradlew`, `gradlew.bat`, or `gradle/wrapper/gradle-wrapper.jar`
— those are generated binaries/scripts, not hand-written. Before the first build, with a local
Gradle install (any recent version) available:

```bash
gradle wrapper --gradle-version 8.9
```

Run from `android/`. Commit the three generated files afterward; `gradle-wrapper.properties`
is already in place and pins the version.
