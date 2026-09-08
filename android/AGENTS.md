# android/AGENTS.md

Kotlin + Compose + Room, package `za.co.dielys`. Layout and rules: see E1/E2 in
`../docs/CODE_STANDARD.md`, and `data/sync/AGENTS.md` before touching sync code.

## Building locally

Needs a local Android SDK (Android Studio, or standalone `cmdline-tools`) with
`ANDROID_HOME`/`local.properties` pointing at it — not provided by this repo.
GitHub-hosted CI runners ship one preinstalled; a plain dev machine may not.

Also needs JDK 21 specifically (matches CI's `actions/setup-java` version) —
`compileOptions`/`kotlinOptions` in `app/build.gradle.kts` target 21, and
building with an older JDK on `JAVA_HOME`/`PATH` fails with `invalid source
release: 21`, not a useful error pointing at the real cause.

```bash
./gradlew ktlintCheck detekt testDebugUnitTest
```

Kotlin 2.0+ needs `org.jetbrains.kotlin.plugin.compose` applied explicitly
alongside `org.jetbrains.kotlin.android` — the Compose compiler was decoupled
from the Kotlin compiler starting Kotlin 2.0. Both `build.gradle.kts` files
already have it; if a "Compose Compiler Gradle plugin is required" error
shows up again, that plugin got dropped somewhere.

`google-services.json` is not committed (gitignored, project-specific), and
the `com.google.gms.google-services` plugin is declared at the root (`apply
false`) but deliberately **not applied** in `app/build.gradle.kts` yet — that
plugin hard-fails at configuration time without the json file, and FCM isn't
wired up yet ([M](../docs/CODE_STANDARD.md#m-push-notifications-fcm) is still
TODO). When FCM setup starts: drop a real `app/google-services.json` in,
apply the plugin in `app/build.gradle.kts`, then it'll work.
