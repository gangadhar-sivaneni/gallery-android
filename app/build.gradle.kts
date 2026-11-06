plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    testOptions { unitTests.isReturnDefaultValues = true } // android.* stubs (RectF) return defaults in JVM tests
    namespace = "com.ganga.gallery"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ganga.gallery"
        minSdk = 30 // MediaStore trash/favorite APIs
        targetSdk = 36
        versionCode = 4
        versionName = "2.2"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug") // personal sideload
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")
    implementation("io.coil-kt.coil3:coil-gif:3.6.3")
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1") // play/pause buttons in the picture-in-picture window
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.print:print:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
}
