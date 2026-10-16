package com.framer.sense.feature.camera.vlm.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** 只检查官方目录结构；夹具字节不能用于真实推理。 */
class ModelFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    /** 创建无自定义清单、无 model_type 的小目录。@param tie 官方共享嵌入配置片段。@return 临时目录。 */
    private fun fixture(tie: String): File = temporary.newFolder().apply {
        File(this, "config.json").writeText("""{"embedding_file":"embeddings_int4.bin"}""")
        File(this, "llm_config.json").writeText("""{"is_visual":true,"tie_embeddings":$tie}""")
        listOf("llm.mnn", "llm.mnn.weight", "tokenizer.txt", "visual.mnn", "visual.mnn.weight").forEach { File(this, it).writeBytes(byteArrayOf(1, 2, 3)) }
    }

    /** 共享嵌入的列表和对象格式不要求独立 bin；无参数。 */
    @Test fun officialSharedEmbeddingDoesNotRequireSeparateFile() = runBlocking {
        ModelFiles.validate(fixture("[128,256,64,4,32]"))
        ModelFiles.validate(fixture("""{"weight_offset":128,"alpha_offset":256,"alpha_size":64,"quant_bit":4,"quant_block":32}"""))
    }

    private fun gemmaFixture(): File = fixture("[1184815938,1386142530,50331648,4,64]").apply {
        File(this, "config.json").writeText("""{"tokenizer_file":"tokenizer.mtok","embedding_file":"embeddings_int4.bin"}""")
        File(this, "llm_config.json").writeText("""{"model_type":"gemma4","is_visual":true,"is_audio":true,"tie_embeddings":[1184815938,1386142530,50331648,4,64],"ple_embed_file":"ple_embeddings_int4.bin"}""")
        File(this, "tokenizer.txt").renameTo(File(this, "tokenizer.mtok"))
        File(this, "ple_embeddings_int4.bin").writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test fun gemmaVisualAudioPackageCanUseVisionWithoutMutatingOriginalConfig() = runBlocking {
        val dir = gemmaFixture()
        val config = File(dir, "config.json").readBytes()
        val info = File(dir, "llm_config.json").readBytes()
        ModelFiles.validate(dir)
        assertTrue(ModelFiles.gemmaVisualOnly(dir))
        assertArrayEquals(config, File(dir, "config.json").readBytes())
        assertArrayEquals(info, File(dir, "llm_config.json").readBytes())
        val qwen = fixture("[128,256,64,4,32]")
        ModelFiles.validate(qwen)
        assertFalse(ModelFiles.gemmaVisualOnly(qwen))
        assertFalse(ModelFiles.qwen35NonThinking(qwen))
        assertFalse(ModelFiles.qwen35NonThinking(dir))
    }

    @Test fun qwen35UsesNonThinkingAdaptationWithoutChangingOriginalPackage() = runBlocking {
        val dir = fixture("[858798102,1113077782,63569920,4,64]")
        File(dir, "config.json").writeText("""{"jinja":{"context":{"enable_thinking":true}}}""")
        File(dir, "llm_config.json").writeText("""{"model_type":"qwen3_5","is_visual":true,"tie_embeddings":[858798102,1113077782,63569920,4,64]}""")
        val config = File(dir, "config.json").readBytes()
        val info = File(dir, "llm_config.json").readBytes()
        ModelFiles.validate(dir)
        assertTrue(ModelFiles.qwen35NonThinking(dir))
        assertFalse(ModelFiles.gemmaVisualOnly(dir))
        assertArrayEquals(config, File(dir, "config.json").readBytes())
        assertArrayEquals(info, File(dir, "llm_config.json").readBytes())
    }

    @Test fun gemmaStillRequiresPleAndVisionFiles() = runBlocking {
        for (name in listOf("ple_embeddings_int4.bin", "visual.mnn", "tokenizer.mtok")) {
            val dir = gemmaFixture()
            File(dir, name).delete()
            val error = runCatching { ModelFiles.validate(dir) }.exceptionOrNull() as ModelStorageException
            assertEquals(name, error.detail)
        }
    }

    @Test fun audioExceptionDoesNotAllowTextOnlySplitGraphOrOtherAudioModels() = runBlocking {
        for ((flags, reason) in listOf(
            "\"model_type\":\"gemma4\",\"is_visual\":false,\"is_audio\":true" to "VISUAL_REQUIRED",
            "\"model_type\":\"gemma4\",\"is_visual\":true,\"is_audio\":true,\"is_single\":false" to "MULTI_GRAPH_UNSUPPORTED",
            "\"model_type\":\"other\",\"is_visual\":true,\"is_audio\":true" to "AUDIO_MODEL_UNSUPPORTED")) {
            val dir = gemmaFixture()
            File(dir, "llm_config.json").writeText("{$flags}")
            val error = runCatching { ModelFiles.validate(dir) }.exceptionOrNull() as ModelStorageException
            assertEquals(reason, error.detail)
        }
    }

    /** offset 为零仍需要独立嵌入，不能只检查 tie_embeddings 字段是否存在；无参数。 */
    @Test fun zeroOffsetRequiresEmbeddingFile() = runBlocking {
        val dir = fixture("[0,256,64,4,32]")
        assertTrue(runCatching { ModelFiles.validate(dir) }.isFailure)
        File(dir, "embeddings_int4.bin").writeBytes(byteArrayOf(1))
        ModelFiles.validate(dir)
    }

    /** 缺视觉图和 LFS 指针不能通过文件校验；无参数。 */
    @Test fun missingVisionAndPointerFail() = runBlocking {
        val dir = fixture("[128,256,64,4,32]")
        val visual = File(dir, "visual.mnn")
        visual.delete()
        assertTrue(runCatching { ModelFiles.validate(dir) }.isFailure)
        visual.writeText("version https://git-lfs.github.com/spec/v1\noid sha256:fixture")
        assertTrue(runCatching { ModelFiles.validate(dir) }.isFailure)
    }

    /** Git blob 摘要包含对象头，不等同于普通文件 SHA-1；无参数。 */
    @Test fun regularGitBlobHashIncludesHeader() = runBlocking {
        val file = temporary.newFile().apply { writeText("{}") }
        val oid = MessageDigest.getInstance("SHA-1").digest("blob 2\u0000{}".toByteArray()).joinToString("") { "%02x".format(it) }
        ModelFiles.verify(file, 2, gitSha1 = oid)
    }

    /** 空间检查和路径越界在写入前失败；无参数。 */
    @Test fun storageAndTraversalAreRejected() {
        assertTrue(runCatching { ModelFiles.checkSpace(64, 128) }.isFailure)
        assertTrue(runCatching { ModelFiles.child(temporary.root, "../escape") }.isFailure)
    }
}
