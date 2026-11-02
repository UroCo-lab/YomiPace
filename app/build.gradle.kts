plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "jp.cn22.yomipace"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "jp.cn22.yomipace"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.4.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    val releaseStore = providers.environmentVariable("YOMIPACE_KEYSTORE")
    signingConfigs {
        if (releaseStore.isPresent) create("distribution") {
            storeFile = file(releaseStore.get())
            storePassword = providers.environmentVariable("YOMIPACE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("YOMIPACE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("YOMIPACE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStore.isPresent) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.window:window:1.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.44.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.44.0")
    testImplementation("androidx.window:window-testing:1.4.0")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("roborazzi.record", "true")
    maxHeapSize = "2048m"
    val regressionZip = rootProject.file("samples/regression").listFiles()?.filter { it.extension.equals("zip", true) }?.singleOrNull()
    if (regressionZip != null) systemProperty("yomipace.regressionZip", regressionZip.absolutePath)
}

// Local paths are written only to ignored build output for the license audit.
tasks.register("runtimeInventory") {
    doLast {
        val rows = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }
            .map { "${it.moduleVersion.id}\t${it.file.absolutePath}" }
        val target = layout.buildDirectory.file("runtime-artifacts.tsv").get().asFile
        target.parentFile.mkdirs()
        target.writeText(rows.joinToString("\n") + "\n")
    }
}
