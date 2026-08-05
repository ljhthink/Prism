package io.prism.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * 空态插画 —— 知识图谱 Lottie 微动画（几何线稿 + 清新填色）。
 *
 * 取代 ADR-002 原摄影位图：加载手写 Lottie JSON
 * （`assets/animations/prism_knowledge_graph.json`），动画内容：
 * - 中央三棱镜剖面（主色描边）+ 薄荷折射光束
 * - 四周 5 个知识节点（主色/辅色/薄荷色实心圆）按序脉冲浮现
 * - 节点到中央的连接线（青色细线）由近及远渐入
 *
 * 动画节奏参考 lottiefiles 微动规范（0.5–3s、缓入缓出、循环），
 * 参见 Continuous-learning 知识库 `wiki/design/animation-resources.md`。
 */
@Composable
fun KnowledgeGraphEmptyState(
    modifier: Modifier = Modifier,
    size: Dp = 180.dp
) {
    val composition by rememberLottieComposition(
        spec = LottieCompositionSpec.Asset("animations/prism_knowledge_graph.json")
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever
    )

    LottieAnimation(
        composition = composition,
        progress = progress,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit
    )
}