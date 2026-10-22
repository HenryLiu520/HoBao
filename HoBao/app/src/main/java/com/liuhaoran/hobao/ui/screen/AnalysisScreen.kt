package com.liuhaoran.hobao.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuhaoran.hobao.model.TimeFilter
import com.liuhaoran.hobao.ui.chart.CategoryDonut
import com.liuhaoran.hobao.ui.chart.TrendLineChart
import com.liuhaoran.hobao.util.formatAmount
import com.liuhaoran.hobao.viewmodel.AnalysisData
import com.liuhaoran.hobao.viewmodel.CategorySlice
import com.liuhaoran.hobao.viewmodel.TrendGranularity
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel
import java.util.Locale

/**
 * 「分析」页 —— **第 3 步的验收界面：只有数字，没有图。**
 *
 * 【为什么先不做图】
 * 图是数字的一个视图。数字没对上就画图，出了问题你分不清是"算错了"还是"画错了"。
 * 这里先用最朴素的排版把口径跑通，第 4 步再把占比换成环形、趋势换成柱状。
 *
 * 【口径都写在 ViewModel 里，这个文件不做任何计算】
 * 页面只负责"把已有的数字摆出来"。
 */
@Composable
fun AnalysisScreen(vm: ExpenseViewModel, modifier: Modifier = Modifier) {
    val data = vm.analysis

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 时间范围 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ANALYSIS_FILTERS.forEach { filter ->
                FilterChip(
                    selected = vm.analysisFilter == filter,
                    onClick = { vm.onAnalysisFilterChange(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        when {
            data == null -> LoadingCard()

            data.count == 0 -> EmptyCard(data.rangeLabel)

            else -> {
                SummaryCard(data)
                CategoryCard(data)
                TrendCard(data, vm)
            }
        }

        if (data != null && data.count > 0) {
            Text(
                text = "折人民币按每条账**记账当时**的汇率快照计算，不是今天的汇率。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** 只提供这四种：「不限」跨全部历史算日均没有意义 */
private val ANALYSIS_FILTERS = listOf(
    TimeFilter.ThisWeek,
    TimeFilter.ThisMonth,
    TimeFilter.ThisQuarter,
    TimeFilter.ThisYear,
)

@Composable
private fun LoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun EmptyCard(rangeLabel: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("💸", fontSize = 32.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "$rangeLabel 还没有账单",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "记一笔之后这里就会有数字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(data: AnalysisData) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = data.rangeLabel + " · 共 " + data.days + " 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatAmount(data.expenseCnyCents, "CNY ", 2),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                StatChip("${data.count} 笔")
                Spacer(Modifier.width(10.dp))
                // 日均：分母是**区间天数**（含没记账的日子），比的是消费节奏
                // 金额一律带 ISO 代码 —— 全 app 统一，见 文档/10
                StatChip("日均 " + formatAmount(data.avgPerDayCents, "CNY ", 2))
            }
            // 退款 + 净额。**绝不把净额冒充"支出"** ——
            // 只退款不花钱的月份，净额会是负的，那必须明说是"净"，不能说"花了 −20 元"。
            if (data.refundCnyCents != 0L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "退款 " + formatAmount(data.refundCnyCents, "CNY ", 2) +
                        " · 净 " + formatAmount(data.netCnyCents, "CNY ", 2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 收入是另一本账，只给一个数字，不参与占比和趋势
            if (data.incomeCnyCents > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "同区间收入 " + formatAmount(data.incomeCnyCents, "CNY ", 2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CategoryCard(data: AnalysisData) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                text = "分类占比",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            // 环形图只回答"哪块最大"，精确数字由下面的列表给。
            // 两个都要：图给人第一眼的印象，列表给能被引用、能被色盲读的事实。
            CategoryDonut(
                slices = data.slices,
                centerLabel = "总支出",
                centerValue = formatAmount(data.expenseCnyCents, "CNY ", 2),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(224.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            data.slices.forEachIndexed { index, slice ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                CategoryRow(slice)
            }
        }
    }
}

@Composable
private fun CategoryRow(slice: CategorySlice) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分类色点是**辅助**，文字百分比才是主信息 —— 色盲也能读
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(slice.colorArgb)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slice.name, style = MaterialTheme.typography.bodyLarge)
                if (slice.archived) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "已归档",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatAmount(slice.cnyCents, "CNY ", 2),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = String.format(Locale.US, "%.1f%% · %d 笔", slice.fraction * 100, slice.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 趋势折线图。
 *
 * 横轴是连续时间，所以用折线而不是柱状 —— 折线看的是"走势和斜率"，
 * 柱状看的是"每一次各是多少"。
 */
@Composable
private fun TrendCard(data: AnalysisData, vm: ExpenseViewModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "趋势",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                // 显示桶数：既能看出粒度是否合适（"1 年"只有 1 个点就很怪），
                // 也告诉用户这图里到底有多少内容
                Text(
                    text = "${data.trend.size} ${data.granularity.unitZh}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 横轴粒度。5 个芯片在 360dp 里可能挤，套一层横向滚动兜底。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrendGranularity.entries.forEach { g ->
                    FilterChip(
                        selected = data.granularity == g,
                        onClick = { vm.onGranularityChange(g) },
                        label = { Text(g.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            TrendLineChart(
                trend = data.trend,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(start = 8.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}
