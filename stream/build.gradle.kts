plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.naslink.stream"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkTestSources = false
    }
}

dependencies {
    // SMB2/SMB3 protocol (Apache 2.0)
    implementation("com.hierynomus:smbj:0.13.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // smbj logs through SLF4J; without a binding this is a no-op on Android.
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
