plugins {
    id("listen.android.library")
}

android {
    namespace = "com.listentogether.communication"
}

dependencies {
    api(project(":sync"))
    api(project(":audio"))
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
