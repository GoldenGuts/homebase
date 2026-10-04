import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Home Assistant logic shared by the Android app (jvm target) and the iOS app (HomebaseCore.xcframework):
// JSON, states and registries, auto-setup rules, the entity picker model, WebSocket frames, demo data.
// No dependencies beyond the Kotlin standard library.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // iOS targets need a Mac (Xcode); on Linux CI only the JVM half is built.
    if (System.getProperty("os.name").startsWith("Mac")) {
        val xcf = XCFramework("HomebaseCore")
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            it.binaries.framework {
                baseName = "HomebaseCore"
                isStatic = true
                xcf.add(this)
            }
        }
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
