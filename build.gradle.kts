buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin support and pulls its own KGP. Pin the
        // version we actually use so the Compose compiler and KSP stay aligned.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
