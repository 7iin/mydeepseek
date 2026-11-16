package com.simplechat.app.data.sync

import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupFile
import com.simplechat.app.data.BackupPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步的**冲突判定**测试。
 *
 * 这是整个同步里唯一会删数据的地方（覆盖本地旧版本之前要先删），
 * 所以每一条判定都单独钉死：时间相等怎么办、远端更旧怎么办、
 * 本地没有又怎么办。改判定之前这些测试必须先失败。
 */
class SyncMergePlanTest {

    private fun conversation(id: String, updatedAt: Long) = BackupConversation(
        id = id,
        title = "会话 $id",
        mode = "CREATIVE",
        createdAt = 1L,
        updatedAt = updatedAt,
    )

    private fun preset(id: String, updatedAt: Long) = BackupPreset(
        id = id,
        name = "预设 $id",
        content = "内容",
        createdAt = 1L,
        updatedAt = updatedAt,
    )

    private fun backup(
        conversations: List<BackupConversation> = emptyList(),
        presets: List<BackupPreset> = emptyList(),
    ) = BackupFile(exportedAt = 0L, conversations = conversations, presets = presets)

    @Test
    fun `远端独有的会话会被拉取`() {
        val plan = planMerge(
            local = backup(),
            remote = backup(conversations = listOf(conversation("a", 100))),
        )

        assertEquals(listOf("a"), plan.conversations.map { it.id })
        assertTrue(plan.overwritingIds.isEmpty())
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `远端更旧的会话不动本地`() {
        val plan = planMerge(
            local = backup(conversations = listOf(conversation("a", 200))),
            remote = backup(conversations = listOf(conversation("a", 100))),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `远端更新的会话会覆盖本地`() {
        val plan = planMerge(
            local = backup(conversations = listOf(conversation("a", 100))),
            remote = backup(conversations = listOf(conversation("a", 200))),
        )

        assertEquals(listOf("a"), plan.conversations.map { it.id })
        // 覆盖必须先删旧的再导入，否则 MERGE 会按 id 整条跳过
        assertEquals(listOf("a"), plan.overwritingIds)
    }

    @Test
    fun `时间相同保留本地`() {
        val plan = planMerge(
            local = backup(conversations = listOf(conversation("a", 100))),
            remote = backup(conversations = listOf(conversation("a", 100))),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `本地独有的会话不会被上传计划带走`() {
        val plan = planMerge(
            local = backup(conversations = listOf(conversation("local", 900))),
            remote = backup(conversations = listOf(conversation("remote", 100))),
        )

        // 本地多的那条不在"要写入"的名单里 —— 它本来就还在库里
        assertEquals(listOf("remote"), plan.conversations.map { it.id })
    }

    @Test
    fun `预设按时间各自判定`() {
        val plan = planMerge(
            local = backup(presets = listOf(preset("old", 100), preset("same", 500), preset("mine", 900))),
            remote = backup(presets = listOf(preset("old", 200), preset("same", 500), preset("new", 100))),
        )

        assertEquals(listOf("old", "new"), plan.presets.map { it.id })
    }

    @Test
    fun `两种内容都没变时计划为空`() {
        val plan = planMerge(
            local = backup(
                conversations = listOf(conversation("a", 100)),
                presets = listOf(preset("p", 100)),
            ),
            remote = backup(
                conversations = listOf(conversation("a", 100)),
                presets = listOf(preset("p", 100)),
            ),
        )

        assertTrue(plan.isEmpty)
    }
}
