package io.prism.qq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [QqStickerStore] / [QqStickerCatalog]（2026-10-26；US-3122）。 */
class QqStickerStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `内置表情覆盖核心情绪`() {
        assertNotNull(QqStickerCatalog.faceIdFor("开心"))
        assertNotNull(QqStickerCatalog.faceIdFor("难过"))
        assertNotNull(QqStickerCatalog.faceIdFor("无语"))
        assertEquals(null, QqStickerCatalog.faceIdFor("不存在的情绪"))
        assertTrue(QqStickerCatalog.allFaceIds().isNotEmpty())
        assertTrue(QqStickerCatalog.emotions().contains("开心"))
    }

    @Test
    fun `导入表情后可读取文件并挑选`() {
        val store = QqStickerStore(folder.root, clock = { 12345L })
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val id = store.importImage(bytes, "开心")
        assertNotNull(id)
        val file = store.imageFile(id!!)
        assertNotNull(file)
        assertTrue(file!!.isFile)
        assertEquals(1, store.userStickers().size)
        val picked = store.pick("开心", kotlin.random.Random(1))
        assertTrue(picked is QqStickerStore.Sticker.ImageFile)
    }

    @Test
    fun `超限图片被拒绝`() {
        val store = QqStickerStore(folder.root, clock = { 1L })
        val huge = ByteArray(QqStickerStore.MAX_IMAGE_BYTES + 1)
        assertEquals(null, store.importImage(huge, "开心"))
    }

    @Test
    fun `删除表情同时删文件`() {
        val store = QqStickerStore(folder.root, clock = { 999L })
        val id = store.importImage(byteArrayOf(9, 9), "难过")!!
        val file = store.imageFile(id)!!
        assertTrue(store.removeUserSticker(id))
        assertTrue(!file.exists())
        assertTrue(store.userStickers().isEmpty())
    }

    @Test
    fun `从事件学习商城表情且去重`() {
        val store = QqStickerStore(folder.root, clock = { 1L })
        val event = Json.parseToJsonElement(
            """{"message":[{"type":"mface","data":{"emoji_id":"e1","emoji_package_id":"p1","key":"k1","summary":"开心"}}]}"""
        ).jsonObject
        store.learnFromEvent(event)
        store.learnFromEvent(event)
        assertEquals(1, store.learnedMfaces().size)
        assertEquals("e1", store.learnedMfaces().single().emojiId)
    }

    @Test
    fun `学习后可回发同一商城表情`() {
        val store = QqStickerStore(folder.root, clock = { 1L })
        val event = Json.parseToJsonElement(
            """{"message":[{"type":"mface","data":{"emoji_id":"e1","emoji_package_id":"p1","key":"k1","summary":"点赞"}}]}"""
        ).jsonObject
        store.learnFromEvent(event)
        val picked = store.pick("点赞", kotlin.random.Random(0))
        assertTrue(picked is QqStickerStore.Sticker.Mface)
    }

    @Test
    fun `无素材时退回内置表情`() {
        val store = QqStickerStore(folder.root, clock = { 1L })
        val picked = store.pick("不存在", kotlin.random.Random(0))
        assertTrue(picked is QqStickerStore.Sticker.BuiltinFace)
    }

    @Test
    fun `持久化后重新加载`() {
        val first = QqStickerStore(folder.root, clock = { 777L })
        first.importImage(byteArrayOf(1), "开心")
        val second = QqStickerStore(folder.root, clock = { 888L })
        assertEquals(1, second.userStickers().size)
    }
}
