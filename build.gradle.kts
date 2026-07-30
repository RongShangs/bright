// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        classpath(libs.kgp)
        classpath(libs.kbti)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
