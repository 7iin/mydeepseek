package com.simplechat.app.data.sync

import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupPreset
import java.io.BufferedWriter
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 同步载荷的格式：**行式 JSON（JSONL）**。
 *
 * ```
 * {"format":"mydeepseek-sync","version":1,"exportedAt":…,"presets":[…]}
 * {"id":"…","title":"…","messages":[…]}
 * {"id":"…","title":"…","messages":[…]}
 * ```
 *
 * ### 为什么不是一个大 JSON 数组
 *
 * 同步原来的做法是 `exportBackup()` 一次生成整份 JSON 字符串：峰值内存 =
 * **全部会话 + 全部正文 + 全部图片 base64**。几百个带图会话就是几百兆，
 * 实测直接 OOM。JSONL 把"一份文档"拆成"一行一条"：
 *
 * - 写：从数据库逐条取会话 → 序列化一条 → 写一行 → 丢掉，峰值只跟最大的那条有关；
 * - 读：`lineSequence()` 逐行解析，同样只持有一条；
 * - 断点友好：真出问题时能看出坏在第几行，而不是"整个文件解析失败"。
 *
 * 另外**图片根本不进 JSON**：图片是 base64，一张 3MB 的照片会让单行变成 4MB。
 * 图片以内容哈希命名单独上传到 `media/` 目录，JSON 里只留文件名。
 *
 * 与 `BackupFile`（导出/导入用的那个大 JSON）是**两套格式**，刻意不复用：
 * 那个要"一份文件拿在手里能整份导入"，这个要"能不整份读进来"。
 */

/** 载荷首行的格式标识。改格式就换它，好让旧版直接拒绝而不是解析出半份数据。 */
internal const val SyncFormat = "mydeepseek-sync"

/** 载荷版本。1 = JSONL + 外置图片。 */
internal const val SyncVersion = 1

/** 图片在网盘上的目录名（相对同步文件所在目录的同级）。 */
internal const val SyncMediaDir = "media"

/** 首行：格式、版本、导出时间，以及**预设**（很小，一行装得下）。 */
@Serializable
internal data class SyncHeader(
    val format: String = SyncFormat,
    val version: Int = SyncVersion,
    val exportedAt: Long = 0L,
    val presets: List<BackupPreset> = emptyList(),
)

/**
 * JSONL 专用的 Json 实例。
 *
 * ⚠️ **不能带 `prettyPrint`**：一缩进就会往一行里塞换行，JSONL 立刻失效。
 * 与备份用的 `backupJson`（缩进、给人读）是两种用途，各用各的。
 */
internal val syncJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** 逐行写载荷。实现了 [Closeable]，调用方直接 `use { }`。 */
internal class SyncPayloadWriter(private val out: BufferedWriter) : java.io.Closeable {

    fun writeHeader(header: SyncHeader) {
        out.write(syncJson.encodeToString(SyncHeader.serializer(), header))
        out.write("\n")
    }

    fun writeConversation(conversation: BackupConversation) {
        out.write(syncJson.encodeToString(BackupConversation.serializer(), conversation))
        out.write("\n")
    }

    override fun close() {
        out.flush()
        out.close()
    }
}

/** 逐行读载荷。 */
internal object SyncPayload {

    /**
     * 读首行。
     *
     * 返回 null 表示"这不是我们的载荷"（旧格式的大 JSON、被截断的文件、空文件……）。
     * 调用方按**空远端**处理 —— 反正整个流程最后会重新上传一份新的。
     */
    fun readHeader(file: File): SyncHeader? = runCatching {
        if (!file.isFile || file.length() == 0L) return null
        file.bufferedReader().use { reader ->
            val first = reader.readLine() ?: return null
            syncJson.decodeFromString(SyncHeader.serializer(), first)
                .takeIf { it.format == SyncFormat }
        }
    }.getOrNull()

    /**
     * 逐条会话。
     *
     * 单行坏掉**只跳过那一行**并计数：一份 200 个会话的载荷里坏一条，
     * 不该让整次同步失败（其余 199 条照常进来）。计数由调用方通过
     * [conversationsWithFailures] 取。
     */
    fun conversations(file: File): Sequence<BackupConversation> =
        conversationsWithFailures(file).map { it.second }.filterNotNull()

    /** 同上，但同时把"坏掉几行"报出来（成功的那条是 non-null）。 */
    fun conversationsWithFailures(file: File): Sequence<Pair<Int, BackupConversation?>> = sequence {
        if (!file.isFile) return@sequence
        file.bufferedReader().use { reader ->
            reader.readLine() // 首行是 header，已单独读过
            var index = 1
            while (true) {
                val line = reader.readLine() ?: break
                index++
                if (line.isBlank()) continue
                val parsed = runCatching {
                    syncJson.decodeFromString(BackupConversation.serializer(), line)
                }.getOrNull()
                yield(index to parsed)
            }
        }
    }
}

/** 图片在网盘上的相对路径。 */
internal fun mediaRemotePath(name: String): String = "$SyncMediaDir/$name"

/**
 * 冲突判定：远端这条要不要写进本地。
 *
 * **时间相同保留本地** —— 两台设备在同一毫秒各写一次时，任何一侧被覆盖都意味着
 * 另一侧的编辑凭空消失；"不动"至少保证用户手上这台看到的东西是连续的。
 *
 * 抽成纯函数只有一个理由：这是整个同步里唯一会丢数据的地方，必须能被单测钉死。
 */
internal fun shouldPull(remoteUpdatedAt: Long, localUpdatedAt: Long?): Boolean =
    localUpdatedAt == null || remoteUpdatedAt > localUpdatedAt

/**
 * 远端预设里"比本地新"的那部分。
 *
 * 预设按 id 覆盖、**从不删除** —— 它是用户长期积累的资产，不该因为对端删过就消失。
 * `updatedAt` 缺失（老数据为 0）时视为"比本地旧"，不吃掉本地的新版本。
 */
internal fun newerPresets(
    local: List<BackupPreset>,
    remote: List<BackupPreset>,
): List<BackupPreset> {
    if (remote.isEmpty()) return emptyList()
    val localById = local.associateBy { it.id }
    return remote.filter { candidate ->
        val mine = localById[candidate.id]
        mine == null || candidate.updatedAt > mine.updatedAt
    }
}
