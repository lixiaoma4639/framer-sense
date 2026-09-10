package com.framer.sense.feature.camera.vlm

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.data.LocalModelStore
import com.framer.sense.feature.camera.vlm.data.ModelFile
import com.framer.sense.feature.camera.vlm.data.ModelManifest
import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 仅验证导入事务和文件约束，测试字节不会加载成推理模型。 */
class LocalModelStoreTest {
    /** 创建与真实模型安装完全隔离的上下文。@param root 测试私有根目录。 */
    private fun isolatedContext(root: File): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        /** 返回测试专用目录；无参数，不接触用户导入模型。 */
        override fun getNoBackupFilesDir(): File = root
        /** 隔离测试激活指针。@param name 存储名；@param mode Android 访问模式。 */
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("test-${root.name}-$name", mode)
    }

    /** 创建完整测试 ZIP，所有流在返回前关闭。
     * @param root 测试目录。
     * @param brokenHash 是否故意损坏视觉文件摘要。
     * @param omitVision 是否缺失视觉组件。
     * @return ZIP 文件。
     */
    private fun modelZip(root: File, brokenHash: Boolean = false, omitVision: Boolean = false): File {
        val files = linkedMapOf(
            "config.json" to "{}".toByteArray(),
            "llm_config.json" to """{"is_visual":true,"model_type":"qwen3_vl"}""".toByteArray(),
            "llm.mnn" to byteArrayOf(1), "llm.mnn.weight" to byteArrayOf(2),
            "tokenizer.txt" to byteArrayOf(3), "visual.mnn" to byteArrayOf(4),
            "embeddings_bf16.bin" to byteArrayOf(5)
        )
        if (omitVision) files.remove("visual.mnn")
        val manifest = ModelManifest("Qwen3-VL-2B-Instruct", "MNN-3.6.1", files.map { (name, bytes) ->
            ModelFile(name, bytes.size.toLong(), if (brokenHash && name == "visual.mnn") "0".repeat(64) else MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        })
        val zip = File(root, "${UUID.randomUUID()}.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            (files + ("manifest.json" to VlmJson.encodeToString(manifest).toByteArray())).forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
        return zip
    }

    /** 损坏包和缺失视觉组件不能覆盖已激活模型；无参数，结束清理测试数据。 */
    @Test fun failedImportPreservesActiveModel() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "vlm-test-${UUID.randomUUID()}").apply { mkdirs() }
        val store = LocalModelStore(isolatedContext(root))
        try {
            store.importPackage(Uri.fromFile(modelZip(root))) { _, _ -> }
            val active = store.activeDirectory()
            assertNotNull(active)
            for (zip in listOf(modelZip(root, brokenHash = true), modelZip(root, omitVision = true))) {
                assertTrue(runCatching { store.importPackage(Uri.fromFile(zip)) { _, _ -> } }.isFailure)
                assertEquals(active, store.activeDirectory())
            }
            assertEquals(1, File(root, "vlm-models").listFiles()!!.size)
        } finally { store.delete(); root.deleteRecursively() }
    }

    /** 空间不足必须在激活前失败并清理暂存目录；无参数。 */
    @Test fun insufficientSpaceDoesNotActivateModel() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "vlm-test-${UUID.randomUUID()}").apply { mkdirs() }
        val store = LocalModelStore(isolatedContext(root), availableSpace = { 0L })
        try {
            val error = runCatching { store.importPackage(Uri.fromFile(modelZip(root))) { _, _ -> } }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("空间不足"))
            assertNull(store.activeDirectory())
            assertTrue(File(root, "vlm-models").listFiles()!!.isEmpty())
        } finally { store.delete(); root.deleteRecursively() }
    }
    /** 不支持的 ABI 在读取模型或加载 JNI 库前返回明确错误；无参数。 */
    @Test fun unsupportedAbiFailsBeforeNativeLoad() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "vlm-test-${UUID.randomUUID()}").apply { mkdirs() }
        val provider = com.framer.sense.feature.camera.vlm.data.MnnProvider(LocalModelStore(isolatedContext(root)), supportedAbis = { arrayOf("x86_64") })
        try {
            val error = runCatching { provider.load() }.exceptionOrNull()
            assertEquals("UNSUPPORTED_ABI", (error as? com.framer.sense.feature.camera.vlm.data.ModelFailure)?.code)
        } finally { provider.unload(); root.deleteRecursively() }
    }

}
