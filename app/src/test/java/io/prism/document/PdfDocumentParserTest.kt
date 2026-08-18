package io.prism.document

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PdfDocumentParser 单元测试（US-012 验收标准 2）。
 *
 * R1（ADR-032）：生产解析器改用 pdfbox-android（依赖 android.graphics，Android 无 java.awt），
 * 纯 JVM 无法加载 → 本测试用 Robolectric 提供 android.graphics 运行，并初始化
 * [PDFBoxResourceLoader]。测试夹具仍由桌面 pdfbox（testImplementation）生成。
 *
 * **application 指定**：`application = android.app.Application::class` 避免 Robolectric 按
 * AndroidManifest 加载 [io.prism.PrismApplication]——后者在 onCreate 初始化 ObjectBox native，
 * 而 Windows JVM 无 ObjectBox native 库（LinkageError），全量回归时毒化 NativeLibraryLoader
 * 静态状态导致测试失败。Robolectric 测试仅需基础 Application 作为 Context。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PdfDocumentParserTest {

    private val parser = PdfDocumentParser("sample.pdf")

    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun parse_extracts_text_from_pdf() {
        val text = parser.parse(TestDocumentFactory.pdfByteStream("Hello Prism PDF"))
        assertTrue("应能抽取 PDF 文本，实际: [$text]", text.contains("Hello Prism PDF"))
    }

    @Test
    fun parse_invalid_pdf_throws() {
        val invalid = "not-a-pdf".byteInputStream()
        assertThrows(DocumentParseException::class.java) {
            parser.parse(invalid)
        }
    }

    @Test
    fun parse_empty_pdf_returns_empty_or_whitespace() {
        val text = parser.parse(TestDocumentFactory.pdfByteStream(""))
        assertEquals("空 PDF 应产生空文本", "", text.trim())
    }
}