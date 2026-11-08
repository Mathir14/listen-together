plugins {
    id("listen.android.library")
}

android {
    namespace = "com.listentogether.voice"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:network"))
    implementation(project(":protocol"))
    implementation(project(":audio"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.webrtc)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
