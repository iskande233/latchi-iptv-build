plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.latchi.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.latchi.iptv"
        minSdk = 21
        targetSdk = 34
        // versionCode موحّد مع Codemagic/GitHub Releases.
        // إذا Codemagic أرسل BUILD_VERSION_CODE نستعمله، وإلا fallback timestamp.
        // المهم: نفس الرقم المبني داخل APK هو نفس الرقم المنشور في Google Sheet.
        val buildTimestamp = (System.currentTimeMillis() / 1000).toInt()
        versionCode = (System.getenv("BUILD_VERSION_CODE")?.toIntOrNull() ?: buildTimestamp)
        versionName = (System.getenv("BUILD_VERSION_NAME") ?: "2.1.1")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/latchi.jks")
            storePassword = "latchi2026"
            keyAlias = "latchi"
            keyPassword = "latchi2026"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures{
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    //for exoplayer
    implementation(libs.exoplayerCore)
    implementation(libs.exoplayerUi)

    //for playing online content
    implementation(libs.exoplayerDash)
    implementation(libs.androidx.fragment)
    implementation(libs.cronet.embedded)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // SmoothBottomBar was unused; removed to avoid JitPack timeout in CI.
    // Fragment library
    implementation("androidx.fragment:fragment-ktx:1.6.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("com.google.code.gson:gson:2.8.8")

    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    implementation ("com.airbnb.android:lottie:4.2.2")

    // SpeedTest Library
    implementation("fr.bmartel:jspeedtest:1.32.1")
    // Removed unused/fragile JitPack gauge and TastyToasts dependencies; native widgets are used instead.

    implementation ("de.hdodenhof:circleimageview:3.1.0")

//    implementation ("com.github.halilozercan:BetterVideoPlayer:2.0.0-alpha01")
//    implementation ("com.github.halilozercan:BetterVideoPlayer:v1.1.0")
//    implementation ("com.github.halilozercan:BetterVideoPlayer:1.1.0")

//    implementation(project(mapOf("path" to ":bettervideoplayer")))

    // Coroutines Library
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0") // For older versions of LiveData

    // Offline-first stack
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // or
//    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.7.1" )// For the latest versions with Kotlin extensions
//    implementation ("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.1") // For ViewModel
    // Add other dependencies here
}