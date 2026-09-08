# android/AGENTS.md

Kotlin + Compose + Room, package `za.co.dielys`. Layout and rules: see E1/E2 in
`../docs/CODE_STANDARD.md`, and `data/sync/AGENTS.md` before touching sync code.

## Building locally

Needs a local Android SDK (Android Studio, or standalone `cmdline-tools`) with
`ANDROID_HOME`/`local.properties` pointing at it — not provided by this repo.
GitHub-hosted CI runners ship one preinstalled; a plain dev machine may not.

Also needs JDK 21 specifically (matches CI's `actions/setup-java` version) —
`compileOptions`/`compilerOptions` in `app/build.gradle.kts` target 21, and
building with an older JDK on `JAVA_HOME`/`PATH` fails with `invalid source
release: 21`, not a useful error pointing at the real cause.

```bash
./gradlew ktlintCheck detekt testDebugUnitTest
```

## Toolchain: AGP 9 has built-in Kotlin

**There is deliberately no `org.jetbrains.kotlin.android` plugin in this
build.** AGP 9.0 absorbed Kotlin support; applying the standalone plugin on
top is a hard error ("no longer required for Kotlin support since AGP 9.0").
Don't "fix" a Kotlin problem by re-adding it.

`org.jetbrains.kotlin.plugin.compose` *is* still separate and still required —
the Compose compiler was decoupled from the Kotlin compiler in Kotlin 2.0 and
did not get absorbed into AGP. If a "Compose Compiler Gradle plugin is
required" error appears, that one got dropped.

Kotlin compiler options use the `compilerOptions` DSL, not `kotlinOptions`:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
```

`kotlinOptions { jvmTarget = "21" }` is an *error*, not a warning, on current
Kotlin — it fails config with "Using 'jvmTarget: String' is an error".

These versions move as a locked set — bumping one alone fails:

| Piece | Version | Constrained by |
|---|---|---|
| Gradle | 9.6.0 | AGP 9.4 requires ≥ 9.6.0 |
| AGP | 9.4.0 | Hilt 2.60.1 requires AGP ≥ 9.0.0 |
| Hilt | 2.60.1 | Older Hilt breaks on current KSP (classloader error) |
| KSP | 2.3.11 | Dropped the `<kotlin>-<ksp>` version scheme at 2.3.0 |
| Compose compiler plugin | 2.4.10 | Must match the Kotlin version AGP uses |
| compileSdk / targetSdk | 37 | compose-bom 2026.08.00 requires ≥ 37 |

Dependabot will keep proposing these one at a time; each one alone will fail
CI. They need bumping together, in one PR.

`google-services.json` is not committed (gitignored, project-specific), and
the `com.google.gms.google-services` plugin is declared at the root (`apply
false`) but deliberately **not applied** in `app/build.gradle.kts` yet — that
plugin hard-fails at configuration time without the json file, and FCM isn't
wired up yet ([M](../docs/CODE_STANDARD.md#m-push-notifications-fcm) is still
TODO). When FCM setup starts: drop a real `app/google-services.json` in,
apply the plugin in `app/build.gradle.kts`, then it'll work.
