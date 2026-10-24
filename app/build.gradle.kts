plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gdhapp.client"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.gdhapp.client"
        minSdk = 29
        targetSdk = 35
        versionCode = 44
        versionName = "2.12.4"
        val githubRepository = providers.gradleProperty("githubRepository").getOrElse("")
        require(githubRepository.isEmpty() || githubRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        buildConfigField("String", "GITHUB_REPOSITORY", "\"$githubRepository\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.gradleProperty("targetAbi").orNull?.let { abi ->
            require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) { "Unsupported targetAbi: $abi" }
            ndk.abiFilters += abi
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libsingbox_exec.so"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(fileTree("libs") { include("*.aar") })
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
