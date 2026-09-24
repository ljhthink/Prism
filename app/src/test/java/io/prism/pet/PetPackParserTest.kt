package io.prism.pet

import io.prism.pet.asset.PetPackException
import io.prism.pet.asset.PetPackLimits
import io.prism.pet.asset.PetPackParser
import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetAppearanceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次24（ADR-051 D1/D4）：`pet.json` 解析、安装期安全边界与模型引用 URL 化单测。
 */
class PetPackParserTest {

    private val minimalLive2D = """
        {
          "schema": "prism-petpack/1",
          "id": "cyrene",
          "name": "昔涟",
          "kind": "live2d",
          "model": "Cyrene.model3.json",
          "actions": { "happy": "开心眼", "wave": "动作#6#0" },
          "idle": ["wave"],
          "tap": "happy"
        }
    """.trimIndent()

    private val minimalImage = """
        { "schema": "prism-petpack/1", "id": "my-pet", "kind": "image", "images": ["portrait.png"] }
    """.trimIndent()

    // ==================== 解析 ====================

    @Test
    fun `解析 - live2d 最小清单`() {
        val m = PetPackParser.parse(minimalLive2D)
        assertEquals("cyrene", m.id)
        assertEquals("昔涟", m.name)
        assertEquals(PetAppearanceKind.LIVE2D, m.kind)
        assertEquals("Cyrene.model3.json", m.model)
        assertEquals(setOf("happy", "wave"), m.actions.keys)
        assertEquals(listOf("wave"), m.idleAliases)
        assertEquals("happy", m.tapAlias)
    }

    @Test
    fun `解析 - 裸字符串一律视为表情`() {
        val m = PetPackParser.parse(minimalLive2D)
        assertEquals(PetActionKind.EXPRESSION, m.actions["happy"]!!.kind)
        assertEquals("开心眼", m.actions["happy"]!!.target)
        // "动作#6#0" 里的 # 属于**表达式名**而非 motion 下标（简写不支持动作，见 shorthandRef KDoc）
        assertEquals(PetActionKind.EXPRESSION, m.actions["wave"]!!.kind)
        assertEquals("动作#6#0", m.actions["wave"]!!.target)
    }

    /**
     * 消歧回归：motion **组名本身含 `#`**（`动作#6`）。简写若按"末段是数字即为下标"猜，
     * 会把 `动作#6` 猜成「组 `动作` 下标 6」——该组不存在，用户点了没反应且无任何提示。
     * 现在规则定死为"简写=表情、动作必须全写"，本用例钉住该契约。
     */
    @Test
    fun `解析 - 含井号的组名不会被误判为动作下标`() {
        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"image","images":["a.png"],
             "actions":{"a":"动作#6","b":"表情#2",
                        "c":{"kind":"motion","target":"动作#6","index":3}}}
            """.trimIndent()
        )
        assertEquals(PetActionKind.EXPRESSION, m.actions["a"]!!.kind)
        assertEquals("动作#6", m.actions["a"]!!.target)
        assertEquals(PetActionKind.EXPRESSION, m.actions["b"]!!.kind)
        // 全写形式才能表达 motion，且组名原样保留
        assertEquals(PetActionKind.MOTION, m.actions["c"]!!.kind)
        assertEquals("动作#6", m.actions["c"]!!.target)
        assertEquals(3, m.actions["c"]!!.index)
    }

    /**
     * 回归：`index = -1` 是"组内随机"的哨兵值，**必须原样穿过解析器**。
     *
     * 早期实现用 `coerceAtLeast(0)` 钳制下标，把 -1 悄悄变成 0——昔涟包的 `idle`
     * 别名本是"待机时随机挑一个待机动作"，被钳制后**永远只播第 0 个动作**。
     * 这类"看起来正常工作、只是没有变化"的缺陷在真机上极难发现，故用用例钉死。
     *
     * 注意：别名键经 [PetActionCatalog.normalizeAlias] **统一小写**，故本用例的键必须全小写
     * （混写大小写会得到 null，是测试自身的坑，不是产品行为）。
     */
    @Test
    fun `解析 - 负下标哨兵表示组内随机且不被钳制`() {
        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"live2d","model":"m.model3.json",
             "actions":{
               "idle":{"kind":"motion","target":"Tick3","index":-1},
               "zero":{"kind":"motion","target":"Tick3","index":0},
               "wild":{"kind":"motion","target":"Tick3","index":99999},
               "negwild":{"kind":"motion","target":"Tick3","index":-99999}
             }}
            """.trimIndent()
        )
        assertEquals(-1, m.actions["idle"]!!.index)
        assertEquals(0, m.actions["zero"]!!.index)
        assertEquals(4096, m.actions["wild"]!!.index)
        assertEquals(-1, m.actions["negwild"]!!.index)
        assertEquals(-1, io.prism.pet.model.PetActionRef.RANDOM_MOTION_INDEX)
    }

    @Test
    fun `解析 - 全写形式含 fallbackExpression`() {        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"image","images":["a.png"],
             "actions":{"wave":{"kind":"motion","target":"动作#6","index":1,
                                "name":"Wink~","fallbackExpression":"表情回正"}}}
            """.trimIndent()
        )
        val ref = m.actions["wave"]!!
        assertEquals(PetActionKind.MOTION, ref.kind)
        assertEquals(1, ref.index)
        assertEquals("Wink~", ref.displayName)
        assertEquals("表情回正", ref.fallbackExpression)
    }

    @Test
    fun `解析 - 别名键归一化为小写 坏项丢弃不整包失败`() {
        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"image","images":["a.png"],
             "actions":{"  Happy  ":"开心眼","broken":"","   ":"x","ok":{"target":""}}}
            """.trimIndent()
        )
        assertEquals(setOf("happy"), m.actions.keys)
    }

    @Test
    fun `解析 - 命中区映射`() {
        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"image","images":["a.png"],
             "hitActions":{"左侧秋千":"WAVE"," 右侧秋千 ":"happy","空":"  "}}
            """.trimIndent()
        )
        assertEquals("wave", m.hitActions["左侧秋千"])
        assertEquals("happy", m.hitActions["右侧秋千"])
        assertFalse(m.hitActions.containsKey("空"))
    }

    @Test
    fun `解析 - 未知字段忽略`() {
        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"image","images":["a.png"],
             "futureField":{"deep":[1,2,3]},"anotherUnknown":"v"}
            """.trimIndent()
        )
        assertEquals("x", m.id)
    }

    @Test
    fun `解析 - 数值字段宽松且钳制`() {
        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"image","images":["a.png"],
             "scale":"999","frameDurationMs":"250","expressionResetSeconds":"999"}
            """.trimIndent()
        )
        assertEquals(4.0f, m.scale, 0.0001f)
        assertEquals(250, m.frameDurationMs)
        assertEquals(60, m.expressionResetSeconds)
    }

    // ==================== 畸形与拒绝 ====================

    @Test
    fun `解析 - 畸形 JSON 抛可读异常`() {
        assertThrows(PetPackException::class.java) { PetPackParser.parse("{ not json ") }
    }

    @Test
    fun `解析 - 顶层非对象拒绝`() {
        assertThrows(PetPackException::class.java) { PetPackParser.parse("[1,2,3]") }
    }

    @Test
    fun `解析 - schema 缺失或版本不符拒绝`() {
        assertThrows(PetPackException::class.java) { PetPackParser.parse("""{"id":"x"}""") }
        assertThrows(PetPackException::class.java) {
            PetPackParser.parse("""{"schema":"prism-petpack/9","id":"x","kind":"image","images":["a.png"]}""")
        }
    }

    @Test
    fun `解析 - kind 非法拒绝`() {
        assertThrows(PetPackException::class.java) {
            PetPackParser.parse("""{"schema":"prism-petpack/1","id":"x","kind":"hologram"}""")
        }
    }

    @Test
    fun `解析 - live2d 无 model 拒绝 image 无 images 拒绝`() {
        assertThrows(PetPackException::class.java) {
            PetPackParser.parse("""{"schema":"prism-petpack/1","id":"x","kind":"live2d"}""")
        }
        assertThrows(PetPackException::class.java) {
            PetPackParser.parse("""{"schema":"prism-petpack/1","id":"x","kind":"image"}""")
        }
    }

    @Test
    fun `解析 - model 路径逃逸拒绝`() {
        assertThrows(PetPackException::class.java) {
            PetPackParser.parse(
                """{"schema":"prism-petpack/1","id":"x","kind":"live2d","model":"../../etc/passwd"}"""
            )
        }
        assertThrows(PetPackException::class.java) {
            PetPackParser.parse(
                """{"schema":"prism-petpack/1","id":"x","kind":"live2d","model":"/abs/model3.json"}"""
            )
        }
    }

    @Test
    fun `解析 - 图片项非法路径被丢弃`() {
        val m = PetPackParser.parse(
            """
            {"schema":"prism-petpack/1","id":"x","kind":"image",
             "images":["a.png","../evil.png","/root.png","b/../c.png","ok/2.png"]}
            """.trimIndent()
        )
        assertEquals(listOf("a.png", "ok/2.png"), m.images)
    }

    // ==================== 路径净化（安全边界） ====================

    @Test
    fun `路径净化 - 接受正常相对路径`() {
        assertEquals("a/b/c.png", PetPackLimits.sanitizeRelativePath("a/b/c.png"))
        assertEquals("动作#6_0.motion3.json", PetPackLimits.sanitizeRelativePath("动作#6_0.motion3.json"))
    }

    @Test
    fun `路径净化 - 拒绝穿越 绝对路径 反斜杠 控制字符`() {
        val rejected = listOf(
            "../evil.png",
            "a/../../evil.png",
            "./a.png",
            "a/./b.png",
            "/etc/passwd",
            "\\windows\\system32",
            "a\\b.png",
            "C:/windows/x.png",
            "a//b.png",
            "a/",
            "",
            "   ",
            "a/\u0000b.png",
            "a/\u001Fb.png",
            null
        )
        rejected.forEach { raw ->
            assertNull("应拒绝: $raw", PetPackLimits.sanitizeRelativePath(raw))
        }
    }

    @Test
    fun `路径净化 - 超长路径拒绝`() {
        assertNull(PetPackLimits.sanitizeRelativePath("a".repeat(PetPackLimits.MAX_PATH_LENGTH + 1)))
    }

    @Test
    fun `扩展名白名单`() {
        assertTrue(PetPackLimits.extensionAllowed("a/model.moc3"))
        assertTrue(PetPackLimits.extensionAllowed("a/TEXTURE_0.PNG"))
        assertTrue(PetPackLimits.extensionAllowed("pet.json"))
        assertFalse(PetPackLimits.extensionAllowed("evil.apk"))
        assertFalse(PetPackLimits.extensionAllowed("evil.so"))
        assertFalse(PetPackLimits.extensionAllowed("noext"))
        assertFalse(PetPackLimits.extensionAllowed("trailing."))
    }

    @Test
    fun `形象 id 净化 - 目录名安全`() {
        assertEquals("cyrene", PetPackLimits.sanitizeAppearanceId("cyrene"))
        assertEquals("a_b", PetPackLimits.sanitizeAppearanceId("a/b"))
        assertEquals("a_b", PetPackLimits.sanitizeAppearanceId("..a/b.."))
        assertEquals("pet", PetPackLimits.sanitizeAppearanceId("///"))
        assertEquals("pet", PetPackLimits.sanitizeAppearanceId(null))
        assertFalse(PetPackLimits.sanitizeAppearanceId("..").contains(".."))
    }

    // ==================== 模型引用 URL 化 ====================

    /**
     * 本批次最关键的回归：`动作#6_0.motion3.json` 里的 `#` 在 URL 中是 fragment 分隔符，
     * 上游的 `removeUrlParams`（`split("#")[0]`）会把后缀整段吃掉 → 请求 404 → 4/5 个
     * motion 静默取不到。安装期改写为 `%23` 是修复点，必须钉死。
     */
    @Test
    fun `引用 URL 化 - 井号与中文被编码 且不影响动作名与参数`() {
        val src = """
            {
              "Version": 3,
              "FileReferences": {
                "Moc": "model.moc3",
                "Textures": ["texture_0.png"],
                "Physics": "model.physics3.json",
                "Motions": {
                  "动作#6": [
                    { "Name": "动作回正", "File": "motions/动作#6_0.motion3.json",
                      "WrapMode": 2,
                      "VarFloats": [ { "Name": "OFF/ON", "Type": 2, "Code": "assign 1" } ] }
                  ]
                },
                "Expressions": [ { "Name": "开心眼", "File": "expressions/6.exp3.json" } ]
              }
            }
        """.trimIndent()

        val out = PetPackParser.encodeModelReferences(src)

        // 文件路径被编码
        assertTrue(out.contains("motions/%E5%8A%A8%E4%BD%9C%236_0.motion3.json"))
        assertTrue(out.contains("\"Moc\": \"model.moc3\""))
        assertTrue(out.contains("expressions/6.exp3.json"))
        // 组名键、动作名、参数表达式**不得**被编码（否则动作按名字查找全部失效）
        assertTrue("组名键必须保留", out.contains("\"动作#6\""))
        assertTrue("动作名必须保留", out.contains("\"动作回正\""))
        assertTrue("参数表达式必须保留", out.contains("\"Code\": \"assign 1\""))
        assertTrue("参数名必须保留", out.contains("\"OFF/ON\""))
        // 结果必须是合法 JSON 且可再次解析
        assertTrue(PetPackParser.collectModelReferences(out).contains("motions/%E5%8A%A8%E4%BD%9C%236_0.motion3.json"))
    }

    @Test
    fun `引用 URL 化 - 畸形输入原样返回不破坏`() {
        assertEquals("{ not json", PetPackParser.encodeModelReferences("{ not json"))
    }

    @Test
    fun `路径编码 - 保留 unreserved 编码其余`() {
        assertEquals("a-b_c.d~e/f", PetPackParser.encodePathForUrl("a-b_c.d~e/f"))
        assertEquals("a%20b.png", PetPackParser.encodePathForUrl("a b.png"))
        assertEquals("a%2Bb.png", PetPackParser.encodePathForUrl("a+b.png"))
        assertEquals("%E5%8A%A8%E4%BD%9C%236_0.motion3.json", PetPackParser.encodePathForUrl("动作#6_0.motion3.json"))
    }

    @Test
    fun `引用收集 - 覆盖官方字段并忽略第三方扩展块`() {
        val src = """
            {
              "FileReferences": {
                "Moc": "model.moc3",
                "Textures": ["t0.png", "t1.png"],
                "Physics": "p.json",
                "Motions": { "g": [ {"File":"m/0.json"}, {"Name":"no-file"} ] },
                "Expressions": [ {"File":"e/0.json"} ]
              },
              "Controllers": { "IntimacySystem": {}, "MouseTracking": {"Enabled": true} }
            }
        """.trimIndent()
        val refs = PetPackParser.collectModelReferences(src)
        assertTrue(refs.containsAll(listOf("model.moc3", "t0.png", "t1.png", "p.json", "m/0.json", "e/0.json")))
        assertEquals(6, refs.size)
    }

    @Test
    fun `引用收集 - 畸形 JSON 返回空 且过滤非法路径`() {
        assertTrue(PetPackParser.collectModelReferences("nope").isEmpty())
        assertTrue(
            PetPackParser.collectModelReferences("""{"FileReferences":{"Moc":"../x.moc3"}}""").isEmpty()
        )
    }

    // ==================== 单图合成清单 ====================

    @Test
    fun `单图合成 - 生成 image 类型清单`() {
        val m = PetPackParser.singleImageManifest("my pet", "我的形象", "portrait.png")
        assertEquals(PetAppearanceKind.IMAGE, m.kind)
        assertEquals(listOf("portrait.png"), m.images)
        assertEquals("my_pet", m.id)
    }

    @Test
    fun `单图合成 - 非法扩展名拒绝`() {
        assertThrows(PetPackException::class.java) {
            PetPackParser.singleImageManifest("x", "x", "evil.apk")
        }
    }
}
