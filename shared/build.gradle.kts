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
}

kotlin {
  android {
    // Must differ from the app's com.example; the Kotlin packages don't change.
    namespace = "io.github.inspectorgad.kuvolleyball.shared"
    compileSdk = 36
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
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
    }
    androidMain.dependencies {
      implementation(libs.okhttp)
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

// The app compiles for Java 11; code inlined into it must not be newer.
tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}
