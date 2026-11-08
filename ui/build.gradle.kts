plugins {
    id("listen.android.library")
    id("listen.compose")
}

android {
    namespace = "com.listentogether.ui"

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":sync"))
    api(project(":music:api"))
    implementation(project(":playback"))
    implementation(project(":voice"))
    implementation(project(":communication"))
    implementation(project(":audio"))
    implementation(project(":core:data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
