plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val evaluationSourcePatterns = listOf(
    "io/redi/robot/showcase/**", "io/redi/robot/HugoFirstLifeApplication.kt",
    "io/redi/robot/hugo/**", "io/redi/robot/core/**", "io/redi/robot/safety/**",
    "io/redi/robot/memory/**", "io/redi/robot/context/**", "io/redi/robot/capability/**",
    "io/redi/robot/manifest/**", "io/redi/robot/trust/**", "io/redi/robot/connection/**",
    "io/redi/robot/discovery/**", "io/redi/robot/provider/**", "io/redi/robot/adapter/**",
    "io/redi/robot/device/**", "io/redi/robot/gateway/**", "io/redi/robot/ai/**", "io/redi/robot/service/**",
    "io/redi/robot/conversation/**", "io/redi/robot/voice/**", "io/redi/robot/skills/**",
    "io/redi/robot/interaction/**", "io/redi/robot/firstlife/**",
    "io/redi/robot/feature/hihugo/presentation/**",
    "io/redi/robot/feature/hihugo/ui/HiHugoSvgFace.kt", "io/redi/robot/platform/storage/**",
)
// Materialize only the reviewed first-party source set. No committed duplicate,
// Kotlin source-set filter ambiguity, or permission suppression is required.
val prepareEvaluationSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include(evaluationSourcePatterns)
        exclude("io/redi/robot/platform/storage/LooiProviderIdentityRepository.kt")
    }
    into(layout.buildDirectory.dir("generated/evaluation-source"))
}

android {
    namespace = "io.redi.robot"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.redi.hugo.showcase"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-showcase"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Explicit source selection: no live BLE, camera, microphone or network
    // backend, original MainActivity or connected-device service enters this APK.
    sourceSets.getByName("main") {
        java.setSrcDirs(listOf(layout.buildDirectory.dir("generated/evaluation-source")))
        res.setSrcDirs(listOf("../app/src/main/res"))
        assets.setSrcDirs(listOf("../app/src/main/assets"))
    }
}

kotlin.sourceSets.getByName("main").kotlin.apply {
    setSrcDirs(listOf(layout.buildDirectory.dir("generated/evaluation-source")))
}

tasks.matching { it.name.startsWith("compile") && (it.name.endsWith("Kotlin") || it.name.endsWith("JavaWithJavac")) }
    .configureEach { dependsOn(prepareEvaluationSources) }

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
