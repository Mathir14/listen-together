plugins {
    id("listen.android.library")
}

android {
    namespace = "com.listentogether.playback"
}

dependencies {
    api(project(":sync"))
    api(project(":music:api"))
    api(project(":audio"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
