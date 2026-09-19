plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hevel.skin"
    compileSdk = 35
    defaultConfig {
        applicationId = "hevel.skin.loader"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "S3_v0001.9"
    }
    sourceSets.getByName("main").java.srcDir("../core/src")
}
