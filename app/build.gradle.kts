plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oksen.spiderhost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oksen.spiderhost"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "0.0.14"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Fixed debug key committed on purpose (self-use debug builds only), so each CI build installs over the last one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "spiderhostdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Spike: no shrinking, so host CatVod / QuickJS stub classes keep their exact names.
            isMinifyEnabled = false
        }
        release {
            // Not used by CI. If ever enabled, host classes MUST be kept (see proguard-rules.pro).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }

    packaging {
        jniLibs {
            // Extract native libs to disk, like classic TVBox hosts.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // Libraries type-3 spider jars commonly link against at runtime (host-provided, parent-first).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Fallback DNS for the host's own clients (Net.kt).
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.collection:collection:1.4.0")
    implementation("androidx.annotation:annotation:1.8.0")

    // Lets the jar reflect into ActivityThread (Kototoro does the same, scoped).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Local 127.0.0.1 HTTP server (/health, /proxy).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
