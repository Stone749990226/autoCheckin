plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.autocheckin"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.personal.autocheckin"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
