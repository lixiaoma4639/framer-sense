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
