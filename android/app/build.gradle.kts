import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "za.co.dielys"
    compileSdk = 37

    defaultConfig {
        applicationId = "za.co.dielys"
        minSdk = 26
        targetSdk = 37
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        getByName("test") {
            // The fixtures are the shared contract (F4). Read the protocol's own
            // copy rather than a duplicate here that could silently drift.
            resources.directories.add(rootProject.file("../protocol/fixtures").path)
        }
    }

    testOptions {
        // Without this Gradle runs the JUnit 4 runner and every JUnit 5 test is
        // silently skipped — a green build that tested nothing (H1).
        unitTests.all { it.useJUnitPlatform() }
        // Robolectric needs the merged manifest and resources to build a Context.
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "SYNC_BASE_URL",
                "\"https://dielys-dev.dielys.workers.dev/\"",
            )
        }
        release {
            buildConfigField(
                "String",
                "SYNC_BASE_URL",
                "\"https://dielys-prod.dielys.workers.dev/\"",
            )
            isMinifyEnabled = false
            // fallbackToDestructiveMigration() is forbidden in release builds — G2.
        }
    }
}

// G2 requires the exported schema JSON to be committed, so migrations can be
// tested against the real previous schema rather than a remembered one.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    // Both are already on the runtime classpath transitively, but the UI calls
    // them directly — `viewModel()` and `collectAsStateWithLifecycle()` — and a
    // direct call on a transitive dependency breaks the day something upstream
    // stops pulling it. Not in the Compose BOM: that covers androidx.compose.* only.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("com.google.firebase:firebase-messaging-ktx:24.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Room needs an android.content.Context even for an in-memory database, so
    // the tests that touch it run under Robolectric — still the JVM, still no
    // emulator (H1). Those are JUnit 4, which the vintage engine runs on the
    // same JUnit Platform as everything else.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Declared rather than downloaded by Robolectric at first run, so Gradle
    // caches it and CI does not fetch 90 MB on every build.
    testImplementation("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}
