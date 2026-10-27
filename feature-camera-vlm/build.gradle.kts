/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Locale

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// 两端共用的唯一默认配置；编译到 BuildConfig，不依赖设备运行时文件路径。
val cloudModelsFile = rootProject.layout.projectDirectory.file("config/cloud-models.json")
val cloudModels = JsonSlurper().parseText(providers.fileContents(cloudModelsFile).asText.get()) as Map<*, *>
fun cloudModelFields(values: Map<*, *>, prefix: String = "MODEL"): Map<String, String> =
    values.entries.flatMap { (key, value) ->
        val name = "${prefix}_${key.toString().uppercase(Locale.ROOT)}"
        if (value is Map<*, *>) cloudModelFields(value, name).entries.map { it.key to it.value }
        else {
            require(value is String) { "cloud-models.json 的 $name 必须为字符串" }
            listOf(name to JsonOutput.toJson(value))
        }
    }.toMap()

android {
    namespace = "com.framer.sense.feature.camera.vlm"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        cloudModelFields(cloudModels).forEach { (name, value) -> buildConfigField("String", name, value) }
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Camera preview and frame analysis
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.google.ar:core:1.54.0")

    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.serialization.json)
//    implementation(libs.okhttp)
    implementation(libs.filament.android)
    implementation(libs.filamat.android)
    implementation(libs.gltfio.android)


    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":core-database"))
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
