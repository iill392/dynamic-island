package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    /**
     * 录制标志：LayerBackdropNode 录制本层内容时置 true。
     * 捕获范围内的液态玻璃元素（如主页 LiquidButton）在录制期间应跳过回放本层，
     * 避免在 GraphicsLayer.record 期间回放同一 layer 导致渲染崩溃；
     * 正常帧（false）时回放真实内容即可获得完整的高斯模糊/折射效果。
     */
    internal var recording: Boolean = false

    /**
     * 主层是否已成功录制过至少一次。
     * 在 LayerBackdropNode 成功 recordLayer 后置 true。
     * 主层从未录制时（如原生首帧、HTML 从未绘制）直接 drawLayer 一个空的
     * RenderNode 会导致 Skia 原生崩溃（SIGSEGV，Kotlin runCatching 无法捕获），
     * 因此 drawBackdrop 必须在此守卫下跳过回放。
     */
    internal var hasRecordedOnce: Boolean = false

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        // 录制期间不回放本层：防止在 GraphicsLayer.record 期间回放同一 layer 导致渲染崩溃
        if (recording) return
        // 主层从未录制过（如原生首帧、HTML 模式从未绘制一帧）时跳过回放：
        // 直接 drawLayer 一个空的 RenderNode 会导致 Skia 原生崩溃（SIGSEGV，runCatching 无法捕获）
        if (!hasRecordedOnce) return
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        // 防御：size 未就绪（0 或非法）时跳过绘制。
        // 首帧布局未完成时 size 可能为 0，layerBlock 中的除法会产生 NaN，
        // NaN 传入原生变换矩阵会导致 Skia/GPU native 崩溃（进程直接死亡）。
        if (size.width <= 0f || size.height <= 0f || !size.width.isFinite() || !size.height.isFinite()) return
        runCatching {
            withTransform({
                if (layerBlock != null) {
                    with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
                }
                val offset =
                    try {
                        layerCoordinates.localPositionOf(coordinates)
                    } catch (_: Exception) {
                        // TODO: outer transformations lead to wrong position calculation
                        coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                    }
                translate(-offset.x, -offset.y)
            }) {
                drawLayer(graphicsLayer)
            }
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
