import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "in_.weenja.hawidgets"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.weenja.hawidgets"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "3.0.0"
        // Beta builds (-Pbeta, or a v*-beta* tag in CI) unlock every widget while people test
        val beta = providers.gradleProperty("beta").isPresent
        buildConfigField("boolean", "BETA", beta.toString())
        if (beta) versionNameSuffix = "-beta"
    }

    // Release signing: put a keystore.properties next to this file (see keystore.properties.example).
    // Without one the debug key signs the build, which is fine for sideloading on your own phone.
    val ksProps = rootProject.file("keystore.properties")
    if (ksProps.exists()) {
        val props = Properties().apply { ksProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksProps.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["debug"].kotlin.srcDirs("src/debug/kotlin")
    packaging { resources.excludes += setOf("META-INF/*.md", "META-INF/*.txt") }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Google Play's own billing library: the one-time Homebase Pro purchase
    implementation("com.android.billingclient:billing:8.0.0")
}
