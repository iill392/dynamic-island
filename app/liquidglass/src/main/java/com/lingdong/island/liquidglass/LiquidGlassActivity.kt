package com.lingdong.island.liquidglass

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

/**
 * v8.45: the second, independent Liquid-Glass island module.
 *
 * It is intentionally separate from the Canvas IslandView/IslandService: the current module is
 * untouched apart from the settings entry. The island material and its buttons are rendered with
 * the open-source AndroidLiquidGlass (Kyant0) code in this module, including blur, vibrancy and
 * lens/refraction.
 */
class LiquidGlassActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LiquidGlassScreen() }
        // The module is a real overlay, not just a preview: stop the original island service and
        // start the liquid-glass subclass of IslandService. The original implementation is reused
        // for notifications/music/charging/unlock; only the island material changes.
        enableLiquidIsland()
    }

    private fun enableLiquidIsland() {
        stopService(Intent().setClassName(packageName, "com.lingdong.island.IslandService"))
        stopService(Intent().setClassName(packageName, "com.lingdong.island.LiquidIslandService"))
        val intent = Intent().setClassName(packageName, "com.lingdong.island.LiquidIslandService")
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Throwable) {
            try { startService(intent) } catch (_: Throwable) {}
        }
    }
}

private val states = listOf("灵动岛", "通知", "音乐", "充电", "解锁")

@Composable
private fun LiquidGlassScreen() {
    var state by remember { mutableIntStateOf(0) }

    // The background is the recorded backdrop that every glass surface replays. Glass elements
    // must stay outside this capture layer to avoid self-reference.
    val backdrop = rememberLayerBackdrop {
        drawRect(
            Brush.linearGradient(
                listOf(
                    Color(0xFF4466DD),
                    Color(0xFF8C5AE8),
                    Color(0xFFDD6D9A),
                    Color(0xFFE7B36B)
                )
            )
        )
        drawContent()
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF4466DD),
                            Color(0xFF8C5AE8),
                            Color(0xFFDD6D9A),
                            Color(0xFFE7B36B)
                        )
                    )
                )
                .layerBackdrop(backdrop)
        )

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "液态玻璃灵动岛",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 42.dp, bottom = 12.dp)
            )

            // Liquid-glass island. This is the same island silhouette the current module uses,
            // but the surface is backdrop blur + vibrancy + lens refraction.
            Box(
                Modifier
                    .width(330.dp)
                    .height(84.dp)
                    .drawPlainBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(18.dp.toPx())
                            lens(18.dp.toPx(), 34.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.16f))
                            drawRect(Color.Black.copy(alpha = 0.10f))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    Modifier.padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(46.dp)
                            .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(24.dp))
                    )
                    Column {
                        Text(
                            states[state],
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "液态玻璃 · 折射预览",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Text(
                "点击下面按钮切换灵动岛状态",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidButton(
                    onClick = { state = 1 },
                    backdrop = backdrop,
                    surfaceColor = Color.White.copy(alpha = 0.32f)
                ) { Text("通知", color = Color.White) }
                LiquidButton(
                    onClick = { state = 2 },
                    backdrop = backdrop,
                    surfaceColor = Color.White.copy(alpha = 0.32f)
                ) { Text("音乐", color = Color.White) }
            }
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiquidButton(
                    onClick = { state = 3 },
                    backdrop = backdrop,
                    surfaceColor = Color.White.copy(alpha = 0.32f)
                ) { Text("充电", color = Color.White) }
                LiquidButton(
                    onClick = { state = 4 },
                    backdrop = backdrop,
                    surfaceColor = Color.White.copy(alpha = 0.32f)
                ) { Text("解锁", color = Color.White) }
            }
            LiquidButton(
                onClick = { state = 0 },
                backdrop = backdrop,
                modifier = Modifier.padding(top = 10.dp),
                surfaceColor = Color.White.copy(alpha = 0.32f)
            ) { Text("返回灵动岛", color = Color.White) }
        }
    }
}
