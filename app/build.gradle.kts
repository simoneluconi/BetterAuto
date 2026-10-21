plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.simoneluconi.betterauto"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.simoneluconi.betterauto"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
