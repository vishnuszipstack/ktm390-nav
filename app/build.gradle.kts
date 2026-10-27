import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.ktmnav"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ktmnav"
        minSdk = 26
        targetSdk = 36
        // CI sets BUILD_NUMBER to the Actions run number, which is also the release tag (build-N).
        val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"vishnuszipstack/ktm390-nav\"")
    }

    // One fixed key for every build, so in-app updates install over the previous version.
    signingConfigs {
        create("ktmnav") {
            storeFile = rootProject.file("signing/ktmnav.jks")
            storePassword = "ktm390-nav"
            keyAlias = "ktmnav"
            keyPassword = "ktm390-nav"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ktmnav")
        }
        release {
            signingConfig = signingConfigs.getByName("ktmnav")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.07.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests (the Android one is a stub on the JVM).
    testImplementation("org.json:json:20240303")
}
