/*
 * Copyright (C) 2022 The Android Open Source Project
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

import org.gradle.api.artifacts.transform.*
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** 保留官方 OpenCV 代码和原生库，统一使用工程 NDK 的共享 C++ 运行库。 */
abstract class OpenCvRuntimeTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("opencv-")) { outputs.file(input); return }
        val output = outputs.file(input.nameWithoutExtension + "-project-runtime.aar")
        ZipInputStream(input.inputStream()).use { source ->
            ZipOutputStream(output.outputStream()).use { target ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    if (!entry.name.matches(Regex("jni/[^/]+/libc\\+\\+_shared\\.so"))) {
                        target.putNextEntry(ZipEntry(entry.name))
                        source.copyTo(target)
                        target.closeEntry()
                    }
                    source.closeEntry()
                }
            }
        }
    }
}

// Root build.gradle.kts

// 在共同父项目声明插件，供子模块复用同一插件类路径，避免 Kotlin 插件重复加载。
// apply false 只准备插件，不把 Android 或 Kotlin 插件应用到根项目。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt.gradle) apply false
    alias(libs.plugins.ksp) apply false
}

// 让 App 与库的所有消费路径使用同一转换，避免传递依赖重新带入 OpenCV 的运行库。
val projectCppRuntime = Attribute.of("com.framer.sense.project-cpp-runtime", Boolean::class.javaObjectType)
subprojects {
    configurations.configureEach { attributes.attribute(projectCppRuntime, true) }
    dependencies {
        artifactTypes.maybeCreate("aar").attributes.attribute(projectCppRuntime, false)
        registerTransform(OpenCvRuntimeTransform::class) {
            from.attribute(Attribute.of("artifactType", String::class.java), "aar").attribute(projectCppRuntime, false)
            to.attribute(Attribute.of("artifactType", String::class.java), "aar").attribute(projectCppRuntime, true)
        }
    }
}
