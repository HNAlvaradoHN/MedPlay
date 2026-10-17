import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.medplay.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.medplay.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.media3:media3-common:1.8.1")
    implementation("androidx.media3:media3-effect:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
    implementation("androidx.media3:media3-transformer:1.8.1")
    implementation("cn.enaium.webrtc.aec3:webrtc-aec3-kmp:1.0.3")
    testImplementation("junit:junit:4.13.2")
}

