package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.VlmJson
import com.framer.sense.feature.camera.vlm.model.newVlmId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import com.framer.sense.feature.camera.vlm.market.MarketCatalog
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

@Serializable data class ModelFile(val path: String, val size: Long, val sha256: String)
@Serializable data class ModelManifest(val model: String, val runtime: String, val files: List<ModelFile>)

@Serializable data class InstalledModel(
    val id: String, val directory: String, val source: String = "", val repository: String = "",
    val revision: String = "", val installedAt: Long = System.currentTimeMillis()
)

/** 管理官方模型目录与旧 ZIP；只有串行原生加载成功后才提交激活指针。 */
class LocalModelStore(private val context: Context, private val availableSpace: (File) -> Long = { it.usableSpace }) {
    val root = File(context.noBackupFilesDir, "vlm-models").canonicalFile.apply { mkdirs() }
    private val preferences = context.getSharedPreferences("vlm-model", Context.MODE_PRIVATE)

    private val _installed = MutableStateFlow(readInstalled())
    val installed = _installed.asStateFlow()
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    private fun readInstalled(): List<InstalledModel> {
        val records = runCatching { VlmJson.decodeFromString<List<InstalledModel>>(preferences.getString("installed", null) ?: "[]") }
            .getOrDefault(emptyList()).filter { runCatching { ModelFiles.child(root, it.directory).isDirectory }.getOrDefault(false) }.toMutableList()
        // 旧安装/已校验候选仅登记元数据，目录和下载断点保持原样。
        listOfNotNull(activeDirectory(), pendingDirectory()).forEach { dir ->
            if (records.none { it.directory == dir.name }) records += InstalledModel(
                if (dir.name.startsWith("download-")) MarketCatalog.DEFAULT_MODEL else "local-${dir.name}", dir.name)
        }
        check(preferences.edit().putString("installed", VlmJson.encodeToString(records)).commit())
        return records
    }

    @Synchronized fun register(dir: File, id: String, source: String = "", repository: String = "", revision: String = "") {
        check(dir.canonicalFile.parentFile == root && dir.isDirectory)
        val record = InstalledModel(id, dir.name, source, repository, revision)
        saveInstalled(_installed.value.filterNot { it.directory == dir.name } + record)
    }

    private fun saveInstalled(records: List<InstalledModel>) {
        if (!preferences.edit().putString("installed", VlmJson.encodeToString(records)).commit()) throw ModelStorageException(R.string.vlm_model_persist)
        _installed.value = records
        changed()
    }
    private fun changed() { _changes.value = _changes.value + 1 }
    fun installedModel(id: String): InstalledModel? = installed.value.filter { it.id == id }.maxByOrNull { it.installedAt }
    fun modelId(dir: File? = activeDirectory()): String? = dir?.let { path -> installed.value.firstOrNull { it.directory == path.name }?.id }

    /** 获取当前安装；无参数，目录丢失时返回 null。 */
    fun activeDirectory(): File? = directory("active")
    /** 获取已校验但尚未激活的候选；无参数，不存在时返回 null。 */
    fun pendingDirectory(): File? = directory("pending")
    /** 读取私有目录指针。@param key 指针名称。@return 存在的安全目录。 */
    private fun directory(key: String): File? = preferences.getString(key, null)?.let { name ->
        runCatching { safeChild(root, name).takeIf { it.isDirectory } }.getOrNull()
    }
    /** 获取已安装模型说明；无参数，不将安装等同于已加载。 */
    fun description(): String = message(if (activeDirectory() == null) R.string.vlm_model_absent else R.string.vlm_model_installed)
    /** 返回模型调用记录名称；无参数，未知导入模型不冒充官方具体型号。 */
    fun modelName(): String = modelId()?.takeUnless { it.startsWith("local-") } ?: message(R.string.vlm_model_generic_name)
    /** 获取本地化文案。@param resource 字符串资源。@param args 格式化参数。@return 当前语言文案。 */
    fun message(resource: Int, vararg args: Any): String = context.getString(resource, *args)
    /** 将文件操作异常转成可展示的消息。@param error 原始异常。@return 不含响应正文或密钥的错误。 */
    fun errorMessage(error: Throwable): String = if (error is ModelStorageException) message(error.resource, error.detail) else message(R.string.vlm_model_operation_failed)

    /** 登记候选目录，下载和导入共用；调用前完成文件校验。
     * @param dir 私有候选目录，不能移动已打开的模型文件。
     */
    @Synchronized fun stage(dir: File) {
        if (dir.canonicalFile.parentFile != root.canonicalFile) throw ModelStorageException(R.string.vlm_model_path)
        val previous = pendingDirectory()
        if (!preferences.edit().putString("pending", dir.name).commit()) throw ModelStorageException(R.string.vlm_model_persist)
        previous?.takeIf { it != dir && it != activeDirectory() && installed.value.none { record -> record.directory == it.name } }?.deleteRecursively()
        changed()
    }

    /** 提交已成功加载的模型；必须由 MNN 串行线程调用。
     * @param dir 已加载候选。@return 原安装目录，调用方释放旧句柄后可清理。
     */
    @Synchronized fun activate(dir: File): File? {
        if (dir.canonicalFile.parentFile != root.canonicalFile) throw ModelStorageException(R.string.vlm_model_path)
        val old = activeDirectory()
        if (installed.value.none { it.directory == dir.name }) register(dir, "local-${dir.name}")
        val edit = preferences.edit().putString("active", dir.name)
        if (pendingDirectory() == dir) edit.remove("pending")
        if (!edit.commit()) throw ModelStorageException(R.string.vlm_model_persist)
        changed()
        return old?.takeIf { it != dir }
    }

    /** 移除未激活候选；不会删除当前安装。@param dir 需要丢弃的候选目录。 */
    fun discard(dir: File) {
        if (activeDirectory() == dir || installed.value.any { it.directory == dir.name }) return
        if (pendingDirectory() == dir && !preferences.edit().remove("pending").commit()) throw ModelStorageException(R.string.vlm_model_persist)
        if (dir.canonicalFile.parentFile == root.canonicalFile) dir.deleteRecursively()
    }

    /** 导入旧 ZIP 或普通官方目录 ZIP，只准备候选，不提前替换旧安装。
     * @param uri 系统选择器返回的 ZIP 地址。@param progress 已复制字节数和阶段文案。
     * @return 校验完成的私有目录。
     */
    suspend fun importPackage(uri: Uri, progress: (Long, String) -> Unit): File = withContext(Dispatchers.IO) {
        prepare { staging ->
            var total = 0L
            var count = 0
            val names = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri)?.use { source ->
                ZipInputStream(source.buffered()).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        if (++count > 4096 || !names.add(entry.name)) throw ModelStorageException(R.string.vlm_model_metadata)
                        val target = safeChild(staging, entry.name.trimEnd('/'))
                        if (entry.isDirectory) target.mkdirs() else copyFile(zip, target) { bytes ->
                            total += bytes
                            if (total > ModelFiles.MAX_BYTES) throw ModelStorageException(R.string.vlm_model_metadata)
                            progress(total, message(R.string.vlm_model_copying))
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw ModelStorageException(R.string.vlm_model_read)
        }
    }

    /** 通过 SAF 导入官方文件夹，保持目录结构，不向 JNI 传入 content URI。
     * @param uri 用户授权的目录 URI。@param progress 已复制字节数与说明。
     * @return 私有模型目录；源文件不修改。
     */
    suspend fun importDirectory(uri: Uri, progress: (Long, String) -> Unit): File = withContext(Dispatchers.IO) {
        prepare { staging ->
            var count = 0
            var total = 0L
            val queue = ArrayDeque<Pair<String, String>>()
            queue.add(DocumentsContract.getTreeDocumentId(uri) to "")
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (id, prefix) = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (++count > 4096) throw ModelStorageException(R.string.vlm_model_metadata)
                        val documentId = cursor.getString(0)
                        val name = cursor.getString(1)
                        if (name.contains('/')) throw ModelStorageException(R.string.vlm_model_path)
                        val relative = prefix + name
                        val target = safeChild(staging, relative)
                        if (target.exists()) throw ModelStorageException(R.string.vlm_model_path)
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            target.mkdirs()
                            queue.add(documentId to "$relative/")
                        } else {
                            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(uri, documentId))?.use { input ->
                                copyFile(input, target) { bytes ->
                                    total += bytes
                                    if (total > ModelFiles.MAX_BYTES) throw ModelStorageException(R.string.vlm_model_metadata)
                                    progress(total, message(R.string.vlm_model_copying))
                                }
                            } ?: throw ModelStorageException(R.string.vlm_model_read)
                        }
                    }
                } ?: throw ModelStorageException(R.string.vlm_model_read)
            }
        }
    }

    /** 创建导入事务并登记候选；失败清理本次文件。
     * @param copy 将外部文件复制到暂存目录的动作。@return 候选目录。
     */
    private suspend fun prepare(copy: suspend (File) -> Unit): File {
        val dir = File(root, "import-${newVlmId()}").apply { mkdirs() }
        try {
            copy(dir)
            validateDirectory(dir)
            currentCoroutineContext().ensureActive()
            register(dir, "local-${dir.name}")
            stage(dir)
            return dir
        } catch (error: Throwable) {
            synchronized(this@LocalModelStore) { runCatching { saveInstalled(installed.value.filterNot { it.directory == dir.name }) } }
            dir.deleteRecursively(); throw error
        }
    }

    /** 分块复制文件并检查空间。@param input 借用的输入流，由调用者关闭。@param target 输出文件。
     * @param progress 接收本块字节数；输出流在本函数关闭。
     */
    private suspend fun copyFile(input: InputStream, target: File, progress: (Int) -> Unit) {
        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                ModelFiles.checkSpace(availableSpace(root), count.toLong())
                progress(count)
                output.write(buffer, 0, count)
            }
        }
    }

    /** 校验标准目录；旧包有清单时额外验证所有摘要。
     * @param dir 私有目录，无清单也可以加载。
     */
    suspend fun validateDirectory(dir: File) = withContext(Dispatchers.IO) {
        val manifestFile = File(dir, "manifest.json")
        if (manifestFile.exists()) {
            if (manifestFile.length() !in 1..1024L * 1024) throw ModelStorageException(R.string.vlm_model_metadata)
            val manifest = VlmJson.decodeFromString<ModelManifest>(manifestFile.readText())
            val names = manifest.files.map { it.path }.toSet()
            val actual = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet() - "manifest.json"
            if (names.size != manifest.files.size || names != actual) throw ModelStorageException(R.string.vlm_model_metadata)
            manifest.files.forEach { ModelFiles.verify(safeChild(dir, it.path), it.size, it.sha256) }
        }
        ModelFiles.validate(dir)
    }

    /** 仅删除指定且已卸载的模型；调用方先等待 JNI 释放，其他安装不受影响。 */
    suspend fun deleteDirectory(dir: File) = withContext(Dispatchers.IO) {
        synchronized(this@LocalModelStore) {
            if (dir.canonicalFile.parentFile != root) throw ModelStorageException(R.string.vlm_model_path)
            val edit = preferences.edit()
            if (activeDirectory() == dir) edit.remove("active")
            if (pendingDirectory() == dir) edit.remove("pending")
            if (!edit.commit()) throw ModelStorageException(R.string.vlm_model_persist)
            if (dir.exists() && !dir.deleteRecursively()) throw ModelStorageException(R.string.vlm_model_operation_failed)
            saveInstalled(installed.value.filterNot { it.directory == dir.name })
        }
    }

    /** 兼容原入口：仅删除当前模型；无当前安装时删除候选。 */
    suspend fun delete() { (activeDirectory() ?: pendingDirectory())?.let { deleteDirectory(it) } }

    companion object {
        /** 兼容已有调用的安全路径入口。@param root 根目录。@param path 相对路径。@return 内部文件。 */
        fun safeChild(root: File, path: String): File = ModelFiles.child(root, path)
    }
}
