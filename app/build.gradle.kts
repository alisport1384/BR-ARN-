plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bigrocket"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bigrocket"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "CORE_VERSION", "\"embedded-aether\"")
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9's built-in Kotlin support derives the Kotlin JVM target from
    // compileOptions above - the old kotlinOptions{} DSL no longer exists
    // (it came from the org.jetbrains.kotlin.android plugin, which AGP 9
    // now refuses to have applied explicitly at all).
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // libaether.so is a native executable intentionally packaged beside the
    // normal JNI libraries. Legacy extraction guarantees that it is materialized
    // under ApplicationInfo.nativeLibraryDir with executable filesystem mode;
    // running an asset/code_cache copy is not reliable on Android 10+.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.register("verifyAetherEngineAssets") {
    doLast {
        val requiredAbis = listOf("arm64-v8a", "armeabi-v7a")
        requiredAbis.forEach { abi ->
            val engine = file("src/main/assets/aether/$abi/libaether.so")
            if (!engine.isFile || engine.length() == 0L) {
                throw GradleException("Missing Aether engine asset for $abi: ${engine.absolutePath}")
            }
        }
    }
}

tasks.named("preBuild") { dependsOn("verifyAetherEngineAssets") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)

    // Aether UI (embedded into the existing BigRocket screen)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Direct Maven dependencies
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}