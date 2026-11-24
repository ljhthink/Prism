package io.prism.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/**
 * 表单字段 —— 设计规范 v0.4 `.field`。
 *
 * 分组标签（`text-dim` 小字）+ 实心输入行（[PrismPanel] 底 + `line-strong` 描边，10dp 圆角）
 * + 可选辅助说明（`text-faint`）。用于 Provider / MCP / Skill 配置详情页。
 */
@Composable
fun PrismField(
    label: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                color = PrismTextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(bottom = 7.dp)
            )
        }
        // 设计稿 `.field input`：**无圆角框**，只有一条底边细线（`border-bottom: 1px line-strong`），
        // 34dp 高、左对齐、透明底。旧实现是圆角实底框，属另一套表单语言。
        // 设计稿 `.field input`：无圆角框，只有一条**底边细线**。
        // 之前只删了框、漏了这条线 → 输入区在深色背景上边界完全消失（不可用）。
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Color.Transparent)
                .padding(horizontal = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = PrismText, fontSize = 14.sp),
                cursorBrush = SolidColor(PrismText),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true,
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = PrismTextFaint,
                            fontSize = 14.sp
                        )
                    }
                    inner()
                }
            )
            if (trailing != null) trailing()
        }
        // 底边细线（`.field input { border-bottom: 1px solid var(--line-strong) }`）
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PrismLineStrong)
        )
        }
        if (hint != null) {
            Text(
                text = hint,
                color = PrismTextFaint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
