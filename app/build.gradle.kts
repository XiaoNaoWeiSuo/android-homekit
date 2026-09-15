plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.local.mihotspot"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.local.mihotspot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
