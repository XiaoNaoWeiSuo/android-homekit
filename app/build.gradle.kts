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
        testInstrumentationRunner = "dev.local.mihotspot.ReliabilityInstrumentation"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Local release builds must be installable. Use a private signing
            // config for public distribution instead of the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
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
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    // A real org.json for JVM unit tests: the android.jar stub throws on every
    // call, which would make the AirDrop event-contract tests meaningless.
    // Test-only — the APK still uses the platform's org.json.
    testImplementation("org.json:json:20240303")
}

val buildAirDropBackend by tasks.registering(Exec::class) {
    inputs.files(fileTree("../native/airdrop") { exclude("target/**") })
    inputs.file("../build-airdrop-backend.sh")
    outputs.files("src/main/jniLibs/arm64-v8a/libmarionette_awdl.so", "src/main/jniLibs/arm64-v8a/libmarionette_airdrop.so")
    commandLine("bash", rootProject.file("build-airdrop-backend.sh").absolutePath)
}
tasks.named("preBuild").configure { dependsOn(buildAirDropBackend) }
