package com.simplechat.app.data.sync

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachments
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupFile
import com.simplechat.app.data.BackupPreset
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.ImportMode
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.backupJson
import com.simplechat.app.data.userMessage
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
 * WebDAV 同步。
 *
 * ### 同步的是"整份备份"，不是增量
 *
 * 本地与远端的往返都用 `BackupFile`（与「导出所有数据」同一份格式），
 * 于是同步、备份、导入三条链路共用一套模型 —— 加字段只改一处。
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
        try {
            val stored = settings.current()
            if (!stored.syncConfigured) {
                _state.value = SyncState(
                    message = Res.get(R.string.sync_need_config),
                    failed = true,
                )
                return
            }
            val config = stored.davConfig()

            _state.value = SyncState(running = true, stage = SyncStage.DOWNLOAD)
            val remote = client.download(config)?.let { parse(it) }

            _state.value = SyncState(running = true, stage = SyncStage.MERGE)
            val pulled = merge(remote)

            _state.value = SyncState(running = true, stage = SyncStage.UPLOAD)
            val payload = repository.exportBackup().toByteArray()
            client.upload(config, payload)

            settings.setDavLastSyncAt(System.currentTimeMillis())
            _state.value = SyncState(
                message = Res.get(
                    R.string.sync_done,
                    Attachments.formatSize(payload.size),
                    pulled,
                ),
                pulled = pulled,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = SyncState(message = error.userMessage(), failed = true)
        } finally {
            running.unlock()
        }
    }

    /**
     * 把远端合并进本地，返回**拉取（新增或覆盖）的会话数**。
     *
     * 顺序很关键：先删掉本地那份过期的会话，再按 `MERGE` 导入 ——
     * `MERGE` 遇到已存在的 id 会**整条跳过**（它服务的是"多设备汇总"，
     * 语义是"本机的改动更权威"）。想让它真的写进去，就得先让 id 不存在。
     *
     * 顺带保住草稿：删会话会连带清掉草稿（那是设计上的对的做法，草稿不该
     * 跟着已删会话留着），但这里删是为了**换版本**，不是用户主动删 ——
     * 把草稿先存下来、导入完再放回去。
     */
    private suspend fun merge(remote: BackupFile?): Int {
        if (remote == null) return 0

        val local = backupJson.decodeFromString<BackupFile>(repository.exportBackup())
        val plan = planMerge(local, remote)
        if (plan.isEmpty) return 0

        if (plan.overwritingIds.isNotEmpty()) {
            val drafts = plan.overwritingIds.mapNotNull { id ->
                repository.findDraft(id)?.let { id to it }
            }
            repository.deleteConversations(plan.overwritingIds)
            drafts.forEach { (id, text) -> repository.saveDraft(id, text) }
        }

        repository.importParsed(
            BackupFile(
                exportedAt = System.currentTimeMillis(),
                conversations = plan.conversations,
                presets = plan.presets,
            ),
            ImportMode.MERGE,
        )
        return plan.conversations.size
    }

    /** 远端那份文件必须是本应用的备份 —— 别的 JSON 直接拒绝，别把它当空备份覆盖掉。 */
    private fun parse(bytes: ByteArray): BackupFile {
        val file = runCatching {
            backupJson.decodeFromString<BackupFile>(bytes.decodeToString())
        }.getOrNull() ?: throw AppTextException(R.string.sync_err_not_backup)
        if (file.format != BackupFile.FORMAT) throw AppTextException(R.string.sync_err_not_backup)
        return file
    }
}

/**
 * 一次同步要写进本地的内容（纯函数算出来，落库交给调用方）。
 *
 * 抽成纯函数只有一个理由：**这是整个同步里唯一会丢数据的地方** ——
 * 覆盖错了就是把用户几天的稿子删掉。它必须能脱离数据库被单测钉死。
 */
internal data class MergePlan(
    /** 远端更新（或本地没有）的会话，按原顺序。 */
    val conversations: List<BackupConversation> = emptyList(),
    /** 远端更新的预设。 */
    val presets: List<BackupPreset> = emptyList(),
    /** 需要先删掉本地旧版本再导入的会话 id。 */
    val overwritingIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = conversations.isEmpty() && presets.isEmpty()
}

/**
 * 冲突判定：**按条 last-write-wins**，时间相同时保留本地。
 *
 * “相同时间保留本地”不是随手写的：两台设备在同一毫秒各写一次时，
 * 任何一侧被覆盖都意味着另一侧的编辑**凭空消失**，而"不动"至少保证
 * 用户手上这台看到的东西是连续的。
 *
 * 只比较 `updatedAt`，不看消息条数：一条被删掉一半的对话也可能时间更新，
 * 那是用户自己删的，应当以时间新的一方为准。
 */
internal fun planMerge(local: BackupFile, remote: BackupFile): MergePlan {
    val localConversations = local.conversations.associateBy { it.id }
    val localPresets = local.presets.associateBy { it.id }

    val conversations = remote.conversations.filter { candidate ->
        val mine = localConversations[candidate.id]
        mine == null || candidate.updatedAt > mine.updatedAt
    }
    val presets = remote.presets.filter { candidate ->
        val mine = localPresets[candidate.id]
        mine == null || candidate.updatedAt > mine.updatedAt
    }

    return MergePlan(
        conversations = conversations,
        presets = presets,
        overwritingIds = conversations.mapNotNull { candidate ->
            candidate.id.takeIf { localConversations.containsKey(it) }
        },
    )
}

/** 设置 → 连接参数。集中在一处，避免每个调用点各拼一次。 */
internal fun StoredSettings.davConfig() = WebDavClient.Config(
    server = davUrl,
    user = davUser,
    password = davPassword,
    path = davPath,
)
