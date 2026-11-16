package com.simplechat.app.data.sync

import com.simplechat.app.data.Attachment
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupMessage
import com.simplechat.app.data.BackupPreset
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步载荷（JSONL）与冲突判定的测试。
 *
 * 两件事都必须钉死：
 * 1. **格式**：一行一个会话、图片只留文件名。它是"不 OOM"的唯一前提 ——
 *    一旦有人把载荷改回大 JSON，这些断言先失败。
 * 2. **冲突判定**：覆盖错了就是把用户几天的稿子删掉。
 */
class SyncPayloadTest {

    private val temps = mutableListOf<File>()

    @After
    fun cleanup() {
        temps.forEach { runCatching { it.delete() } }
    }

    private fun tempFile(): File =
        File.createTempFile("sync-test", ".jsonl").also { temps += it }

    private fun conversation(id: String, updatedAt: Long, vararg messages: BackupMessage) =
        BackupConversation(
            id = id,
            title = "会话 $id",
            mode = "CREATIVE",
            createdAt = 1L,
            updatedAt = updatedAt,
            messages = messages.toList(),
        )

    private fun message(id: String, attachments: List<Attachment> = emptyList()) = BackupMessage(
        id = id,
        role = "user",
        content = "正文 $id",
        attachments = attachments,
        createdAt = 1L,
    )

    private fun preset(id: String, updatedAt: Long) = BackupPreset(
        id = id,
        name = "预设 $id",
        content = "内容",
        createdAt = 1L,
        updatedAt = updatedAt,
    )

    // ── 载荷格式 ────────────────────────────────────────

    @Test
    fun `载荷逐行写、逐行读，条数与顺序不变`() {
        val file = tempFile()
        SyncPayloadWriter(file.bufferedWriter()).use { writer ->
            writer.writeHeader(SyncHeader(exportedAt = 42L, presets = listOf(preset("p", 1L))))
            writer.writeConversation(conversation("a", 100L))
            writer.writeConversation(conversation("b", 200L))
        }

        // 首行是 header，之后一行一条会话
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)

        val header = SyncPayload.readHeader(file)
        assertNotNull(header)
        assertEquals(SyncFormat, header!!.format)
        assertEquals(1, header.presets.size)

        assertEquals(
            listOf("a", "b"),
            SyncPayload.conversations(file).map { it.id }.toList(),
        )
    }

    @Test
    fun `图片只留文件名，不把字节塞进 JSON`() {
        val file = tempFile()
        val image = Attachment(
            kind = Attachment.Kind.IMAGE,
            name = "photo.jpg",
            mime = "image/jpeg",
            data = "",
            width = 100,
            height = 200,
            path = "0123456789abcdef0123456789abcdef.jpg",
        )
        SyncPayloadWriter(file.bufferedWriter()).use { writer ->
            writer.writeHeader(SyncHeader())
            writer.writeConversation(
                conversation("a", 100L, message("m1", listOf(image))),
            )
        }

        val raw = file.readText()
        assertFalse("载荷里不该出现 base64", raw.contains("base64"))
        val read = SyncPayload.conversations(file).single()
        val attachment = read.messages.single().attachments.single()
        assertEquals("0123456789abcdef0123456789abcdef.jpg", attachment.path)
        assertEquals("", attachment.data)
    }

    @Test
    fun `坏行只跳过自己，其余照常读出来`() {
        val file = tempFile()
        file.writeText(
            buildString {
                appendLine("""{"format":"$SyncFormat","version":$SyncVersion,"exportedAt":1,"presets":[]}""")
                appendLine("""{"id":"ok1","title":"t","mode":"CREATIVE","createdAt":1,"updatedAt":2}""")
                appendLine("{ 这不是 JSON")
                appendLine("""{"id":"ok2","title":"t","mode":"CREATIVE","createdAt":1,"updatedAt":3}""")
            },
        )

        val results = SyncPayload.conversationsWithFailures(file).toList()
        assertEquals(3, results.size)
        assertEquals(listOf("ok1", "ok2"), results.mapNotNull { it.second?.id })
        assertEquals(1, results.count { it.second == null })
    }

    @Test
    fun `旧格式的大 JSON 认不出来，按空远端处理`() {
        val file = tempFile()
        file.writeText("""{"format":"simplechat-backup","version":2,"conversations":[]}""")
        assertNull(SyncPayload.readHeader(file))
    }

    @Test
    fun `空文件与不存在的文件都返回 null`() {
        val file = tempFile()
        assertNull(SyncPayload.readHeader(file))
        assertNull(SyncPayload.readHeader(File(file.parentFile, "does-not-exist.jsonl")))
    }

    // ── 冲突判定 ────────────────────────────────────────

    @Test
    fun `本地没有就一定拉`() {
        assertTrue(shouldPull(remoteUpdatedAt = 100L, localUpdatedAt = null))
        assertTrue(shouldPull(remoteUpdatedAt = 0L, localUpdatedAt = null))
    }

    @Test
    fun `远端更新才覆盖`() {
        assertTrue(shouldPull(remoteUpdatedAt = 200L, localUpdatedAt = 100L))
        assertFalse(shouldPull(remoteUpdatedAt = 100L, localUpdatedAt = 100L))
        assertFalse(shouldPull(remoteUpdatedAt = 50L, localUpdatedAt = 100L))
    }

    @Test
    fun `预设按时间各自判定，老数据的时间为 0 不吃本地`() {
        val local = listOf(preset("same", 500L), preset("mine", 900L))
        val remote = listOf(preset("same", 500L), preset("mine", 0L), preset("new", 100L))

        assertEquals(listOf("new"), newerPresets(local, remote).map { it.id })
        assertTrue(newerPresets(local, emptyList()).isEmpty())
    }
}
