plugins {
    id("listen.android.library")
}

android {
    namespace = "com.listentogether.testing"
}

dependencies {
    api(project(":sync"))
    api(project(":music:api"))
    api(project(":audio"))
    api(project(":communication"))
    api(project(":voice"))
    api(project(":protocol"))
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
