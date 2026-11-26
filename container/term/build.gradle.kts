plugins {
    id("com.android.library")
}

// Vendored from mtty-term (Apache-2.0): industrial VT emulator backed by
// libghostty-vt (MIT). Native libmtty-vt.so extracted from mtty nightly APK.
// Source: github.com/spizganed/mtty-term. Do not edit core VT files upstream-style.
android {
    namespace = "com.spizganed.mtty.term"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
