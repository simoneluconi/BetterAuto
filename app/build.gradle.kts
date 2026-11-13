plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

// The one place for the version (scripts\release.ps1 reads it); the code follows it: 1.2.3 -> 10203
val appVersion = "0.2.0"

// The phone's TLS identity: assets/ holds our public development pair and may hold one of your own (ignored by
// git), and an APK must carry only one (AapTls refuses two). Yours is taken when it is there;
// -Pbetterauto.certs=dev leaves it out (the public release), -Pbetterauto.certs=own insists on it.
val devCertificates = setOf("phone_cert.pem", "phone_key.pem")
val certificateExtensions = setOf("pem", "crt", "cer", "key", "der", "p12", "pfx")
val ownCertificates = file("src/main/assets").listFiles().orEmpty()
    .filter { it.isFile && it.extension.lowercase() in certificateExtensions }
    .map { it.name }.toSet() - devCertificates
val leftOutAssets = when (val choice = providers.gradleProperty("betterauto.certs").orNull) {
    "dev" -> ownCertificates
    "own" -> if (ownCertificates.isNotEmpty()) devCertificates
        else throw GradleException("betterauto.certs=own: no certificate of your own in app/src/main/assets")
    null -> if (ownCertificates.isNotEmpty()) devCertificates else emptySet()
    else -> throw GradleException("betterauto.certs is dev or own, not $choice")
}

android {
    namespace = "com.simoneluconi.betterauto"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.simoneluconi.betterauto"
        minSdk = 29
        targetSdk = 37
        versionName = appVersion
        versionCode = appVersion.split('.').map(String::toInt).let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
        // Phones only: MapLibre's x86 libraries, for emulators, made up a third of the APK
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    androidResources {
        ignoreAssetsPatterns += leftOutAssets.map { "!$it" }
    }

    // Code and native libraries compressed: stored as they are (the default) the APK was twice the size, over
    // what some ways of sharing a file take; the phone unpacks them once at install
    packaging {
        dex { useLegacyPackaging = true }
        jniLibs { useLegacyPackaging = true }
    }

    // The release key stays out of the repository: %USERPROFILE%\.gradle\gradle.properties holds
    // BETTERAUTO_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS and _KEY_PASSWORD. Without them the release is unsigned.
    signingConfigs {
        providers.gradleProperty("BETTERAUTO_STORE_FILE").orNull?.let { store ->
            create("release") {
                storeFile = file(store)
                storePassword = providers.gradleProperty("BETTERAUTO_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("BETTERAUTO_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("BETTERAUTO_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // android.util.Log (EventLog) returns defaults in JVM tests instead of throwing
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        // The interface between the app and its Shizuku service (audio routing as the shell user)
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.protobuf.javalite)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.media3.session)
    implementation(libs.palette)
    // Optional: with Shizuku installed and allowed, audio is moved to the car instead of copied (Apache 2.0)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // Smart home: Home Assistant's HTTP login and WebSocket API (Apache 2.0)
    implementation(libs.okhttp)
    // The map: vector tiles drawn on the phone with our own style (BSD-2-Clause)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // The real org.json for JVM tests (the Android one is a stub there)
    testImplementation(libs.org.json)
}

// .proto files in src/main/proto/aap_protobuf come from opencardev/aasdk (GPLv3), see AASDK_VERSION.txt
protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
