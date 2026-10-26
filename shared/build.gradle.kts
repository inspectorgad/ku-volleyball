import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// The code the Android and iPhone apps share. commonMain is plain Kotlin that
// compiles for both; androidMain and iosMain hold each phone's version of the
// few things that differ (HTTP, storage), behind the interfaces in
// com.example.platform. The Android app depends on this module; the iPhone
// app links the Shared framework built from it.
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  // AGP 9's Android target for multiplatform modules; the older
  // com.android.library cannot be combined with Kotlin Multiplatform.
  alias(libs.plugins.android.kotlin.multiplatform.library)
  // Room's compiler, run once per platform (see dependencies below).
  alias(libs.plugins.google.devtools.ksp)
  // The screens: Compose Multiplatform, and the Compose compiler (same
  // version as Kotlin).
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  android {
    // Must differ from the app's com.example; the Kotlin packages don't change.
    namespace = "io.github.inspectorgad.kuvolleyball.shared"
    compileSdk = 37
    minSdk = 24
    // commonTest runs on the JVM too (testAndroidHostTest), so the answers
    // the iPhone must match are checked where they were recorded.
    withHostTest {}
  }

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "Shared"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      // api: the app uses the database and its DAO directly, and draws its
      // Android-only cards (match alerts) with the same Compose.
      api(libs.androidx.room.runtime)
      api(libs.compose.runtime)
      api(libs.compose.foundation)
      api(libs.compose.ui)
      api(libs.compose.material3)
      api(libs.jetbrains.lifecycle.viewmodel)
      implementation(libs.jetbrains.lifecycle.runtime.compose)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
    }
    androidMain.dependencies {
      implementation(libs.okhttp)
      // Android's back button (SystemBackHandler).
      implementation(libs.androidx.activity.compose)
    }
    iosMain.dependencies {
      implementation(libs.androidx.sqlite.bundled)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    getByName("androidHostTest").dependencies {
      implementation(kotlin("test-junit"))
    }
  }

  // The simulator iosSimulatorArm64Test runs on: CI picks one the runner has
  // and passes its id, since the default device name changes between Xcodes.
  targets.withType<KotlinNativeTargetWithSimulatorTests>().configureEach {
    providers.environmentVariable("IOS_SIMULATOR").orNull?.let { testRuns.getByName("test").deviceId = it }
  }
}

// Room generates the database and DAO code separately for each platform.
dependencies {
  add("kspAndroid", libs.androidx.room.compiler)
  add("kspIosArm64", libs.androidx.room.compiler)
  add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

// List every test with its result, on the JVM and the iPhone simulator alike,
// so the CI log shows the cross-platform checks ran, and how any failed.
tasks.withType<AbstractTestTask>().configureEach {
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

// The app compiles for Java 11; code inlined into it must not be newer.
tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}
