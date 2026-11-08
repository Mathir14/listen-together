plugins {
    id("listen.android.library")
}

android {
    namespace = "com.listentogether.service"
}

dependencies {
    api(project(":sync"))
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":playback"))
    implementation(project(":voice"))
    implementation(project(":communication"))
    implementation(project(":audio"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.session)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
