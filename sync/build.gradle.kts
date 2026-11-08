plugins {
    id("listen.android.library")
}

android {
    namespace = "com.listentogether.sync"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":protocol"))
    api(project(":core:network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
