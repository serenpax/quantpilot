package com.quantpilot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.quantpilot.app.data.Backtester
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Marker(val index: Int, val buy: Boolean)

/**
 * 自绘K线/净值图。
 * - 双指捏合：缩放（改变 visibleCount）
 * - 单指水平拖动：平移图表
 * - 单指垂直拖动：不消费，交给父级 verticalScroll
 * mode=PRICE: 价格线+买卖点；mode=EQUITY: 策略净值 vs 买入持有。
 *
 * visibleCount / startIndex 由外部传入以实现两图联动。
 */
@Composable
fun StockChart(
    result: Backtester.Result,
    mode: String,
    modifier: Modifier = Modifier,
    visibleCount: Float,
    startIndex: Float,
    onViewportChange: (visibleCount: Float, startIndex: Float) -> Unit,
) {
    val n = result.dates.size

    // rememberUpdatedState：pointerInput 持续运行时始终读到最新值
    val latestVisibleCount = rememberUpdatedState(visibleCount)
    val latestStartIndex = rememberUpdatedState(startIndex)
    val latestN = rememberUpdatedState(n)
    val latestCallback = rememberUpdatedState(onViewportChange)

    // 画布宽度，pointerInput 和 Canvas 共享
    var lastWidth by remember { mutableFloatStateOf(0f) }

    val equityLine = Color(0xFF2563EB)
    val benchLine  = Color(0xFF94A3B8)
    val priceLine  = Color(0xFF64748B)
    val buyColor   = Color(0xFFE74C3C)
    val sellColor  = Color(0xFF27AE60)
    val gridColor  = Color(0xFFE8E8E8)
    val labelColor = Color(0xFF888888)

    Box(modifier = modifier.background(Color.White)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        val prevPos = mutableMapOf<Long, Offset>()
                        var prevDistance = 0f
                        // 滑动方向锁：null=未锁定, true=水平(平移图表), false=垂直(交给父级)
                        var directionLocked: Boolean? = null
                        // 累积位移，用于方向锁定判断
                        var accumDx = 0f
                        var accumDy = 0f
                        val lockThreshold = 10.dp.toPx()

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val ptrs = event.changes.filter { it.pressed }

                            if (ptrs.isEmpty()) {
                                prevPos.clear()
                                prevDistance = 0f
                                directionLocked = null
                                accumDx = 0f
                                accumDy = 0f
                                continue
                            }

                            val vc = latestVisibleCount.value
                            val si = latestStartIndex.value
                            val totalN = latestN.value
                            val callback = latestCallback.value

                            if (ptrs.size == 1) {
                                val ptr = ptrs[0]
                                val prev = prevPos[ptr.id.value]
                                prevPos[ptr.id.value] = ptr.position
                                prevDistance = 0f

                                if (prev != null) {
                                    val dx = ptr.position.x - prev.x
                                    val dy = ptr.position.y - prev.y
                                    accumDx += dx
                                    accumDy += dy

                                    // 方向未锁定时积累位移，超过阈值才锁定
                                    if (directionLocked == null) {
                                        val totalAccum = kotlin.math.sqrt(accumDx * accumDx + accumDy * accumDy)
                                        if (totalAccum >= lockThreshold) {
                                            directionLocked = kotlin.math.abs(accumDx) >= kotlin.math.abs(accumDy)
                                        }
                                    }

                                    when (directionLocked) {
                                        true -> {
                                            // 水平锁定：平移图表
                                            ptr.consume()
                                            if (lastWidth > 0f) {
                                                val pxPerBar = lastWidth / vc
                                                val newStart = (si - dx / max(pxPerBar, 0.01f))
                                                    .coerceIn(0f, (totalN - vc).coerceAtLeast(0f))
                                                callback(vc, newStart)
                                            }
                                        }
                                        false -> { /* 垂直锁定：不消费，父级 scroll 接管 */ }
                                        null  -> { /* 方向未锁定，不消费，等待累积 */ }
                                    }
                                }

                            } else {
                                // 双指捏合缩放，重置方向锁
                                directionLocked = null
                                accumDx = 0f
                                accumDy = 0f

                                val p0 = ptrs[0]
                                val p1 = ptrs[1]
                                p0.consume()
                                p1.consume()

                                val cx = (p0.position.x + p1.position.x) / 2f
                                val dist = run {
                                    val ddx = p0.position.x - p1.position.x
                                    val ddy = p0.position.y - p1.position.y
                                    sqrt(ddx * ddx + ddy * ddy)
                                }

                                if (prevDistance > 0f && lastWidth > 0f && dist > 0f) {
                                    val zoom = dist / prevDistance
                                    val newVisible = (vc / zoom).coerceIn(30f, totalN.toFloat())
                                    val anchorIdx = si + (cx / lastWidth) * vc
                                    val newStart = (anchorIdx - (cx / lastWidth) * newVisible)
                                        .coerceIn(0f, (totalN - newVisible).coerceAtLeast(0f))
                                    callback(newVisible, newStart)
                                }
                                prevDistance = dist
                                ptrs.forEach { prevPos[it.id.value] = it.position }
                            }
                        }
                    }
                }
        ) {
            lastWidth = size.width
            if (n < 2 || size.width < 10f) return@Canvas
            val padL = 8.dp.toPx()
            val padR = 56.dp.toPx()
            val padT = 12.dp.toPx()
            val padB = 22.dp.toPx()
            val chartW = size.width - padL - padR
            val chartH = size.height - padT - padB

            val i0 = startIndex.toInt().coerceIn(0, n - 1)
            val i1 = min(n - 1, (startIndex + visibleCount).toInt())
            val count = max(i1 - i0, 2)

            // 数据范围
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (i in i0..i1) {
                val series = if (mode == "EQUITY") floatArrayOf(result.equity[i], result.benchmark[i])
                else floatArrayOf(result.closes[i])
                for (v in series) {
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
            }
            val range = if (maxV - minV < 1e-6f) 1f else (maxV - minV) * 1.08f
            val mid = (maxV + minV) / 2f
            val lo = mid - range / 2f
            fun y(v: Float) = padT + chartH * (1f - (v - lo) / range)
            fun x(i: Int) = padL + chartW * (i - i0).toFloat() / count

            // 网格 + 右侧刻度
            for (g in 0..4) {
                val gy = padT + chartH * g / 4f
                drawLine(gridColor, Offset(padL, gy), Offset(padL + chartW, gy), 1f)
                val v = lo + range * (1f - g / 4f)
                val label = if (mode == "EQUITY") String.format("%.2f", v) else String.format("%.1f", v)
                drawText(label, padL + chartW + 4.dp.toPx(), gy + 4.dp.toPx(), labelColor)
            }

            // 价格 / 净值线
            fun drawSeries(values: FloatArray, color: Color, widthPx: Float) {
                val path = Path()
                for (i in i0..i1) {
                    val px = x(i); val py = y(values[i])
                    if (i == i0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width = widthPx))
            }
            if (mode == "EQUITY") {
                drawSeries(result.benchmark, benchLine, 1.5.dp.toPx())
                drawSeries(result.equity, equityLine, 2.dp.toPx())
            } else {
                drawSeries(result.closes, priceLine, 1.8.dp.toPx())
                val marks = mutableListOf<Marker>()
                for (i in max(1, i0)..i1) {
                    val d = result.positions[i] - result.positions[i - 1]
                    if (d > 0.001f) marks.add(Marker(i, true))
                    else if (d < -0.001f) marks.add(Marker(i, false))
                }
                for (m in marks) {
                    drawCircle(
                        buyColor.takeIf { m.buy } ?: sellColor,
                        radius = 4.dp.toPx(),
                        center = Offset(x(m.index), y(result.closes[m.index])),
                    )
                }
            }

            // 底部日期标签
            val step = count / 4
            for (k in 0..4) {
                val idx = min(i1, i0 + step * k)
                val d = result.dates[idx].substring(5)
                drawText(d, x(idx) - 14.dp.toPx(), size.height - 5.dp.toPx(), labelColor)
            }
        }
    }
}

/** 简易文字绘制。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawText(
    text: String, x: Float, y: Float, color: Color,
) {
    val paint = android.graphics.Paint().apply {
        this.color = android.graphics.Color.rgb(
            (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
        )
        textSize = 10.dp.toPx()
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
