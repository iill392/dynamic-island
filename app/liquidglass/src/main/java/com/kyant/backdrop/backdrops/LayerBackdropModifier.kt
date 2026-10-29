package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.internal.recordLayer

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropElement(backdrop)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        // 关键：先录制本帧内容到主层，再绘制内容到屏幕。
        // 这样捕获范围内元素（如主页 LiquidButton）在 drawContent() 阶段回放主层时，
        // 主层必然已在本帧录制（最新内容），绝不会 drawLayer 一个从未录制的空
        // RenderNode —— 空层回放会导致 Skia 原生崩溃（SIGSEGV，Kotlin 无法捕获）。
        backdrop.recording = true
        try {
            recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
            backdrop.hasRecordedOnce = true
        } finally {
            backdrop.recording = false
        }
        drawContent()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
