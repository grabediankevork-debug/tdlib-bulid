plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Your own Telegram API keys (https://my.telegram.org -> API development tools).
// Put them in ~/.gradle/gradle.properties (not in the project) so they are never shared:
//     tgApiId=1234567
//     tgApiHash=0123456789abcdef0123456789abcdef
// If you leave them out, the app asks for them on first start.
val tgApiId: Int = (project.findProperty("tgApiId") as String?)?.trim()?.toIntOrNull() ?: 0
val tgApiHash: String = ((project.findProperty("tgApiHash") as String?) ?: "").trim()

android {
    namespace = "com.couchreel.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.couchreel.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("int", "TG_API_ID", tgApiId.toString())
        buildConfigField("String", "TG_API_HASH", "\"$tgApiHash\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
