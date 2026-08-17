package io.prism.document

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PptxDocumentParser 单元测试（UXR9 US-907 新增）。
 *
 * 用 TestDocumentFactory（POI XSLF 同源库）在内存构造 PPTX 样例，验证：
 * - 多页文本抽取（含页号标注）
 * - 单页多文本框拼接
 * - 非法输入抛 DocumentParseException
 */
class PptxDocumentParserTest {

    @Test
    fun pptx_extracts_multi_page_text_with_page_markers() {
        val parser = PptxDocumentParser("a.pptx")
        val text = parser.parse(
            TestDocumentFactory.pptxByteStream(
                listOf(
                    listOf("首页标题", "第一页内容"),
                    listOf("第二页标题", "第二页内容")
                )
            )
        )
        assertTrue("应标注第 1 页，实际: [$text]", text.contains("[第 1 页]"))
        assertTrue("应标注第 2 页，实际: [$text]", text.contains("[第 2 页]"))
        assertTrue("应抽取首页文本，实际: [$text]", text.contains("首页标题"))
        assertTrue("应抽取第一页内容，实际: [$text]", text.contains("第一页内容"))
        assertTrue("应抽取第二页标题，实际: [$text]", text.contains("第二页标题"))
    }

    @Test
    fun pptx_single_slide_multiple_textboxes_concatenated() {
        val parser = PptxDocumentParser("a.pptx")
        val text = parser.parse(
            TestDocumentFactory.pptxByteStream(
                listOf(listOf("第一块", "第二块"))
            )
        )
        assertTrue("应抽取第一块，实际: [$text]", text.contains("第一块"))
        assertTrue("应抽取第二块，实际: [$text]", text.contains("第二块"))
    }

    @Test
    fun pptx_invalid_input_throws() {
        val parser = PptxDocumentParser("a.pptx")
        assertThrows(DocumentParseException::class.java) {
            parser.parse("not-a-pptx".byteInputStream())
        }
    }
}
