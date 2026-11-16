package com.simplechat.app.data.sync

import android.content.Context
import com.simplechat.app.R
import com.simplechat.app.data.AttachmentFiles
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.userMessage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 同步进行到哪一步（界面据此给一句话 + 一个转圈）。 */
enum class SyncStage { DOWNLOAD, MERGE, UPLOAD }

/**
 * 同步的对外状态。
 *
 * 刻意做成**一个不可变快照**而不是几个独立的 flag：界面上「正在上传」和
 * 「上次同步失败」同时成立，只会让人以为还在跑。
 */
data class SyncState(
    val running: Boolean = false,
    val stage: SyncStage? = null,
    /** 上一次结果（成功 / 失败都写在这里）。null = 本次启动还没同步过。 */
    val message: String? = null,
    val failed: Boolean = false,
    /**
     * 上一次同步**从远端拉下来**（新增或覆盖）的会话数。
     *
     * 界面据此决定要不要把当前打开的会话从库里重读一遍：
     * 那条会话可能刚被远端版本整条换掉，内存里的消息树已经过期。
     */
    val pulled: Int = 0,
)

/**
 * WebDAV 同步（**流式**）。
 *
 * ### 数据怎么走
 *
 * ```
 * 远端 sync.jsonl ──流式下载──> 临时文件 ──逐行解析──> 逐条比对/落库
 *                                                        │
 * 本地数据库 ──逐条取会话──> 临时文件 ──流式上传──> 远端 sync.jsonl
 *                                   └──图片单独上传──> 远端 media/<sha256>.jpg
 * ```
 *
 * 全程**没有任何一步把整个库读进内存**：
 *
 * - 载荷是 JSONL（一行一个会话），读写都是逐行的（见 [SyncPayload]）；
 * - 图片不进 JSON，按内容哈希单独上传，JSON 里只留文件名；
 * - 上传直接给 OkHttp 一个 `File`，它自己边读边发。
 *
 * 峰值内存 ≈ **最大的一个会话** + 一张图片的字节，与库有多大无关。
 *
 * ### 冲突策略：**按会话 last-write-wins**
 *
 * 以 `conversations.updatedAt` 为准：远端更新就覆盖本地这一条，本地更新就留着，
 * 最后把合并结果整份传回去。不做逐条消息的 diff —— 消息树（分支 / 多版本）
 * 一旦逐条合并，很容易拼出"父消息来自 A 设备、子消息来自 B 设备"的断链。
 *
 * 已知限制（见 README）：**删除不传播**。在 A 设备删掉的会话，会在 B 设备
 * 下次同步时被重新带回来，除非两台设备都删过。要做对得引入墓碑记录，
 * 那会给 messages 加一套"已删除但保留"的状态，对个人使用不划算。
 */
class SyncManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val settings: SettingsStore,
    private val client: WebDavClient = WebDavClient(),
) {

    /** 自己的作用域：同步要能活过设置页的销毁（用户切走再回来，状态还在）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * 同一时刻只跑一次。
     *
     * 自动同步与手动点击可能同时发生；两路并发会把同一份远端读两遍、
     * 再互相覆盖着写回去，浪费流量还可能丢合并结果。
     */
    private val running = Mutex()

    /** 手动「立即同步」。 */
    fun startSync() {
        scope.launch { sync() }
    }

    /**
     * 启动时的自动同步：**只在用户打开开关且配置齐全时**触发一次。
     *
     * 失败静默留在状态里（设置页看得到），不弹窗 —— 启动路径上的任何弹窗
     * 都是打扰，而且这时候用户多半正要开始写字。
     */
    fun syncOnLaunchIfEnabled() {
        scope.launch {
            val stored = settings.current()
            if (stored.davAutoSync && stored.syncConfigured) sync()
        }
    }

    /** 试连。返回一句可直接显示的话。 */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val stored = settings.current()
        if (!stored.syncConfigured) return@withContext Res.get(R.string.sync_need_config)
        runCatching { client.probe(stored.davConfig()) }.fold(
            onSuccess = { Res.get(R.string.sync_test_ok) },
            onFailure = { it.userMessage() },
        )
    }

    // ── 主流程 ───────────────────────────────────────────

    private suspend fun sync() {
        if (!running.tryLock()) return
        val work = File(context.cacheDir, SyncWorkDir).apply { mkdirs() }
        val remote = File(work, "remote.jsonl")
        val incoming = File(work, "incoming.jsonl")
        val payload = File(work, "payload.jsonl")

        try {
            val stored = settings.current()
            if (!stored.syncConfigured) {
                _state.value = SyncState(message = Res.get(R.string.sync_need_config), failed = true)
                return
            }
            val config = stored.davConfig()

            _state.value = SyncState(running = true, stage = SyncStage.DOWNLOAD)
            val hadRemote = client.downloadToFile(config, config.path, remote)

            _state.value = SyncState(running = true, stage = SyncStage.MERGE)
            val merge = merge(config, remote, incoming, hadRemote)

            _state.value = SyncState(running = true, stage = SyncStage.UPLOAD)
            // 先把本地状态写成载荷文件（逐会话），再上传 —— 内存里只过一条会话
            val mediaNames = writeLocalPayload(payload)
            // 图片先走：否则另一端可能读到"引用了还不存在的图片"的载荷
            val mediaUploaded = uploadMedia(config, mediaNames)
            client.uploadFile(config, config.path, payload, WebDavClient.SyncPayloadMedia)

            settings.setDavLastSyncAt(System.currentTimeMillis())
            _state.value = SyncState(
                message = buildResultMessage(merge, mediaUploaded),
                pulled = merge.pulled,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = SyncState(message = error.userMessage(), failed = true)
        } finally {
            running.unlock()
            listOf(remote, incoming, payload).forEach { runCatching { it.delete() } }
        }
    }

    private fun buildResultMessage(merge: MergeOutcome, mediaUploaded: Int): String = buildString {
        append(Res.get(R.string.sync_done, merge.pulled, mediaUploaded))
        if (merge.brokenLines > 0) append(Res.get(R.string.sync_note_broken, merge.brokenLines))
        if (merge.legacyRemoteReplaced) append(Res.get(R.string.sync_note_legacy))
    }

    // ── 合并（逐行） ─────────────────────────────────────

    private data class MergeOutcome(
        val pulled: Int = 0,
        val brokenLines: Int = 0,
        /** 云端原来放的是旧格式/别的文件，这次被新格式整体替换了。 */
        val legacyRemoteReplaced: Boolean = false,
    )

    /**
     * 把远端合并进本地。
     *
     * 顺序很关键：先删掉本地那份过期的会话，再按 `MERGE` 导入 ——
     * `MERGE` 遇到已存在的 id 会**整条跳过**（它服务的是"多设备汇总"，
     * 语义是"本机的改动更权威"）。想让它真的写进去，就得先让 id 不存在。
     *
     * 顺带保住草稿：删会话会连带清掉草稿（那是设计上的对的做法，草稿不该
     * 跟着已删会话留着），但这里删是为了**换版本**，不是用户主动删 ——
     * 把草稿先存下来、导入完再放回去。
     */
    private suspend fun merge(
        config: WebDavClient.Config,
        remote: File,
        incoming: File,
        hadRemote: Boolean,
    ): MergeOutcome {
        val header = SyncPayload.readHeader(remote)
            ?: return MergeOutcome(
                brokenLines = 0,
                legacyRemoteReplaced = hadRemote && remote.length() > 0L,
            )

        val localStamps = repository.syncStamps().associate { it.id to it.updatedAt }
        val overwriting = mutableListOf<String>()
        var pulled = 0
        var broken = 0

        // 逐行读远端 → 需要的写进 incoming，另存一份"要覆盖谁"的名单
        SyncPayloadWriter(incoming.bufferedWriter()).use { writer ->
            for ((_, conversation) in SyncPayload.conversationsWithFailures(remote)) {
                if (conversation == null) {
                    broken++
                    continue
                }
                val mine = localStamps[conversation.id]
                if (!shouldPull(conversation.updatedAt, mine)) continue
                if (mine != null) overwriting += conversation.id
                writer.writeConversation(adoptMedia(config, conversation))
                pulled++
            }
        }

        if (pulled == 0) return MergeOutcome(brokenLines = broken)

        val drafts = overwriting.mapNotNull { id -> repository.findDraft(id)?.let { id to it } }
        if (overwriting.isNotEmpty()) repository.deleteConversations(overwriting)
        drafts.forEach { (id, text) -> repository.saveDraft(id, text) }

        repository.importSyncStream(
            conversations = SyncPayload.conversations(incoming),
            presetsToWrite = newerPresets(repository.syncPresets(), header.presets),
        )
        return MergeOutcome(pulled = pulled, brokenLines = broken)
    }

    /**
     * 让远端会话里的图片**在本地可用**，并把附件改写成"本地文件名"。
     *
     * 远端载荷里的图片只有文件名（内容哈希）。本地没有这个文件时拉一次 ——
     * 文件名就是内容哈希，所以"有"就等于"对"，不需要再校验。
     */
    private suspend fun adoptMedia(
        config: WebDavClient.Config,
        conversation: BackupConversation,
    ): BackupConversation = conversation.copy(
        messages = conversation.messages.map { message ->
            if (message.attachments.none { it.isImage }) return@map message
            message.copy(
                attachments = message.attachments.map { attachment ->
                    if (!attachment.isImage) return@map attachment
                    // 老数据可能还把图片内联在 data 里：先落到本地，再统一成文件名
                    val name = attachment.path?.takeIf { it.isNotBlank() }
                        ?: AttachmentFiles.contentAddressed(context, attachment)
                        ?: return@map attachment.copy(data = "", path = null)
                    ensureMediaFile(config, name)
                    attachment.copy(data = "", path = name)
                },
            )
        },
    )

    /** 本地没有就拉一次；拉不到（远端也缺）就留着空引用，界面显示"原件缺失"。 */
    private suspend fun ensureMediaFile(config: WebDavClient.Config, name: String) {
        val target = AttachmentFiles.fileOf(context, name) ?: return
        if (target.isFile && target.length() > 0L) return
        runCatching { client.downloadToFile(config, mediaRemotePath(name), target) }
    }

    // ── 导出（逐会话写文件） ──────────────────────────────

    /**
     * 把本地状态写成载荷文件，返回**图片文件名**清单（供随后上传）。
     *
     * 一次只取一个会话：`syncStamps()` 给出 id + 时间戳，再逐个
     * `backupConversationForSync(id)` —— 全程不会同时持有两条会话。
     */
    private suspend fun writeLocalPayload(target: File): List<String> {
        val media = linkedSetOf<String>()
        SyncPayloadWriter(target.bufferedWriter()).use { writer ->
            writer.writeHeader(
                SyncHeader(exportedAt = System.currentTimeMillis(), presets = repository.syncPresets()),
            )
            for (stamp in repository.syncStamps()) {
                val conversation = repository.backupConversationForSync(stamp.id) ?: continue
                writer.writeConversation(externalizeMedia(conversation, media))
            }
        }
        return media.toList()
    }

    /** 图片 → 内容寻址的文件名；文本附件保持内联（它们本来就小）。 */
    private fun externalizeMedia(
        conversation: BackupConversation,
        collected: MutableSet<String>,
    ): BackupConversation = conversation.copy(
        messages = conversation.messages.map { message ->
            if (message.attachments.none { it.isImage }) return@map message
            message.copy(
                attachments = message.attachments.map { attachment ->
                    if (!attachment.isImage) return@map attachment
                    val name = AttachmentFiles.contentAddressed(context, attachment)
                    if (name == null) {
                        // 原件缺失（用户清过数据 / 老数据坏了）：不把坏引用传上去
                        attachment.copy(data = "", path = null)
                    } else {
                        collected += name
                        attachment.copy(data = "", path = name)
                    }
                },
            )
        },
    )

    /** 上传远端还没有的图片。返回本次实际传上去的张数。 */
    private suspend fun uploadMedia(config: WebDavClient.Config, names: List<String>): Int {
        if (names.isEmpty()) return 0
        var uploaded = 0
        for (name in names) {
            val file = AttachmentFiles.fileOf(context, name) ?: continue
            if (!file.isFile || file.length() == 0L) continue
            if (runCatching { client.exists(config, mediaRemotePath(name)) }.getOrDefault(false)) continue
            client.uploadFile(config, mediaRemotePath(name), file)
            uploaded++
        }
        return uploaded
    }

    private companion object {
        const val SyncWorkDir = "sync"
    }
}

/** 设置 → 连接参数。集中在一处，避免每个调用点各拼一次。 */
internal fun StoredSettings.davConfig() = WebDavClient.Config(
    server = davUrl,
    user = davUser,
    password = davPassword,
    path = davPath,
)
