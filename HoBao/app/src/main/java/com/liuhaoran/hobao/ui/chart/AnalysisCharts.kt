package com.liuhaoran.hobao.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.viewmodel.CategorySlice
import com.liuhaoran.hobao.viewmodel.TrendPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import kotlin.math.ceil

/** 一屏最多显示多少个点，超过就靠横向滑动 */
private const val VISIBLE_POINTS = 14

/** 点数不超过这个就画数据点圆点（再多就糊成一条粗线） */
private const val DOTS_MAX = 40

/**
 * 分类占比的环形图 —— **自己用 Canvas 画，没有引库**。
 *
 * 【为什么不引图库的饼图】
 * 唯一支持饼图的 Vico 3.3.x 要用 Kotlin 2.4 编译，和本项目的构建链不兼容（见 libs.versions.toml）。
 * 而环形图的几何只有一句话：
 *
 *     每一片的角度 = 占比 × 360°
 *
 * 引一个库来解决这个，等于为了一个乘法拖进几百 KB。
 *
 * 【反过来，折线图为什么该引库】
 * 折线要处理：刻度怎么取整、标签怎么避让、缩放、点击高亮、边界裁切…
 * 这些是"细节多但没意思"的活，正是库的价值所在。
 *
 * —— 判断标准不是"库好不好"，而是**这段逻辑是不是本质复杂**。
 */
@Composable
fun CategoryDonut(
    slices: List<CategorySlice>,
    centerLabel: String,
    centerValue: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 26.dp.toPx()
            // 半径要把线宽算进去，否则圆环会被画布边缘裁掉一半
            val radius = (size.minDimension - strokePx) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcSize = Size(radius * 2, radius * 2)
            val topLeft = Offset(center.x - radius, center.y - radius)

            // 从 12 点方向开始，顺时针
            var startAngle = -90f
            // 片与片之间留一条缝：不留缝的话相邻两片颜色接近就糊成一块
            val gap = if (slices.size > 1) 1.2f else 0f

            slices.forEach { s ->
                val sweep = (s.fraction * 360.0).toFloat()
                if (sweep > gap) {
                    drawArc(
                        color = Color(s.colorArgb),
                        startAngle = startAngle + gap / 2,
                        sweepAngle = sweep - gap,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Butt),
                    )
                }
                startAngle += sweep
            }
        }

        // 中间挖空的部分放总额 —— 这是环形（不是实心饼）的全部理由
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = centerValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 趋势折线图（Vico 3.2.3）。
 *
 * 【为什么折线而不是柱状】
 * 横轴是**连续时间**（天/月），折线表达"变化和斜率"；柱状表达"每一次各是多少"。
 * 记账趋势关心的是"是不是在往上走"，所以折线更对。
 */
@Composable
fun TrendLineChart(
    trend: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    // 【颜色】原来是 rememberM3VicoTheme() 的默认值，也就是 Material You 动态主色 ——
    // 取的是系统壁纸的主色，通常偏灰偏暗。折线是这一页唯一的"看走势"的地方，
    // 灰线看不出重点，所以给一个固定的鲜明色（浅色深色各一套）。
    val accent = if (isSystemInDarkTheme()) Color(0xFF8FA6FF) else Color(0xFF3D5AFE)
    val theme = rememberM3VicoTheme(lineCartesianLayerColors = listOf(accent))
    val producer = remember { CartesianChartModelProducer() }

    // 数据点的圆点。点太多就全是圆点、糊成一条粗线，所以只在点少的时候开。
    val dot = rememberShapeComponent(fill = Fill(accent), shape = CircleShape)

    // 从**最新**那端开始，而不是最老的：打开时想看的通常是"最近怎么样"，
    // 想看历史往右拖。
    //
    // ⚠️ 只给 initialScroll 是不够的。VicoScrollState.update() 里是：
    //
    //     if (!initialScrollHandled) {
    //         value = initialScroll.getValue(..., maxValue)
    //         initialScrollHandled = true
    //     }
    //
    // 而**第一次布局时模型还没到**，maxValue = 0 → value 被钉在 0，
    // 然后 initialScrollHandled 立刻变 true，之后再也不处理。
    // 表现：明明写了 End，却停在最左边。
    //
    // 更坑的是切粒度：从「周」（4 个点，一屏装得下）切到「日」（20 个点），
    // 内容突然变宽，但滚动位置还停在 0 —— 于是又回到最左边。
    // autoScroll 也救不了，它在缩放生效前就取过了 maxValue。
    //
    // 所以真正的解法是在 LaunchedEffect 里、模型和布局都落定之后显式滚一次。
    val scrollState = rememberVicoScrollState(
        scrollEnabled = true,
        initialScroll = Scroll.Absolute.End,
    )

    LaunchedEffect(trend) {
        producer.runTransaction {
            // 用「元」而不是「分」当坐标：纵轴刻度才是 50/100，不是 5000/10000
            // Vico 3.2.3 的 series() 只接受 Collection<Number> 或 vararg Number，
            // 不收 DoubleArray —— 直接传 List<Double>，别 toDoubleArray()
            lineModel { series(trend.map { it.cnyCents / 100.0 }) }
        }
        // 等两帧：一帧把新模型量出来，一帧把新缩放的 layerDimensions 应用上去。
        // 少等这一下，scroll() 拿到的还是旧 maxValue，等于没滚。
        withFrameNanos { }
        withFrameNanos { }
        scrollState.scroll(Scroll.Absolute.End)
    }

    val labels = trend.map { it.label }
    // 一个月 30 个日期标签会糊成一片 —— 最多显示 6 个
    val step = ceil(labels.size / 6.0).toInt().coerceAtLeast(1)

    // ⚠️ 必须显式给 initialZoom。
    // rememberVicoZoomState() 的默认是 initialZoom = Zoom.max(Zoom.fixed(), Zoom.Content)，
    // 而 Zoom.fixed() 默认 1f —— 意思是"装不下就装不下，绝不缩小"。
    // 一个月的 20 个点需要缩到 1× 以下才放得下，结果只画出前 10 天，
    // 而且**不报错**：纵轴、标签、曲线看起来都很正常，只是后半段根本没有。
    // （10 个点的「本年」刚好装得下，所以同一份代码看不出来。）
    //
    // 现在的策略：
    //   点不多（≤14）→ Zoom.Content 一次看全，没有滚动条也就没有困惑
    //   点多       → Zoom.x(14) 只显示 14 个点，其余靠横向滑动看
    //                （293 个点全塞进 360dp 就是一根涂满的墨条，什么都读不出来）
    val initialZoom = remember(trend.size) {
        if (trend.size <= VISIBLE_POINTS) Zoom.Content else Zoom.x(VISIBLE_POINTS.toDouble())
    }
    val zoomState = rememberVicoZoomState(
        zoomEnabled = true,
        initialZoom = initialZoom,
        minZoom = Zoom.Content,   // 允许一路缩到"全装下"
    )
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(accent)),
        // 3dp：默认 2dp 在 3x 屏上偏细，曲线显得"没精神"
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 3.dp),
        // 线下方一层半透明填充：让曲线有"体量"，一眼看出累计了多少
        areaFill = LineCartesianLayer.AreaFill.single(Fill(accent.copy(alpha = 0.16f))),
        // 【单点也能看见】只有一个桶时，折线图本来什么都画不出来 ——
        // 加了圆点之后那一个点就是一个实实在在的圆点，不再是一根空白坐标系。
        pointProvider = if (trend.size <= DOTS_MAX) {
            LineCartesianLayer.PointProvider.single(LineCartesianLayer.Point(dot, size = 7.dp))
        } else {
            null
        },
    )

    ProvideVicoTheme(theme) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(line),
                ),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(
                    // 抽稀标签必须用 ItemPlacer。
                    // ⚠️ 我曾用"formatter 返回空字符串"来隐藏多余的标签 —— Vico 会直接抛
                    //    IllegalStateException: `CartesianValueFormatter.format` returned a blank string
                    //    而且是在**绘制阶段**抛，堆栈里全是 Compose 的 draw，根本看不出是自己的问题。
                    itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { step }),
                    // formatter 现在只负责"把 x 下标翻译成标签"，绝不返回空串。
                    // 兜底也不能是 ""，否则同样的崩溃会从另一条路回来。
                    valueFormatter = CartesianValueFormatter { _, value, _ ->
                        labels.getOrNull(value.toInt()) ?: labels.lastOrNull() ?: "-"
                    },
                ),
            ),
            modelProducer = producer,
            scrollState = scrollState,
            zoomState = zoomState,
            modifier = modifier,
        )
    }
}
