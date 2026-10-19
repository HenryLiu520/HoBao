package com.liuhaoran.hobao.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.model.SortOrder
import com.liuhaoran.hobao.model.TimeFilter
import com.liuhaoran.hobao.model.toLocalDate
import com.liuhaoran.hobao.model.toShortDateText
import com.liuhaoran.hobao.model.toUtcMidnightMillis
import com.liuhaoran.hobao.model.utcMidnightToLocalDay
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel
import java.time.LocalDate

/**
 * 筛选面板（底部弹层）。
 *
 * 布局：左右分栏 + 中间细分隔线
 *   左栏 = 时间（预设 + 自定义时的两个日期按钮）
 *   右栏 = 类别（单选，带色点）
 *   下方 = 排序（横滑标签）
 *   底部 = 重置 / 完成
 *
 * 三个设计决定（都是你自己拍的）：
 * 1. 改动**立即生效** —— 没有"应用"这一步，面板背后的列表实时在变
 * 2. 类别**单选** —— 一次只看一个分类
 * 3. 已归档的分类**不出现在这里** —— 它们不在"可记账"的候选里
 *    （但旧账仍然能显示它们的名字，因为列表是按 id 去 categories 表里查的）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    vm: ExpenseViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 0 = 没开日期选择器；1 = 选起始；2 = 选结束
    var pickerTarget by rememberSaveable { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text("筛选", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            // ── 左右分栏 ──
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {

                // 左：时间
                Column(modifier = Modifier.weight(1f)) {
                    SectionLabel("时间")
                    val presets = listOf(
                        TimeFilter.All, TimeFilter.Today, TimeFilter.ThisWeek,
                        TimeFilter.ThisMonth, TimeFilter.ThisQuarter, TimeFilter.ThisYear,
                    )
                    presets.forEach { f ->
                        OptionRow(
                            label = f.label,
                            selected = vm.timeFilter == f,
                            onClick = { vm.onTimeFilterChange(f) },
                        )
                    }
                    val customSelected = vm.timeFilter is TimeFilter.Custom
                    OptionRow(
                        label = "自定义",
                        selected = customSelected,
                        onClick = { vm.onCustomSelected() },
                    )
                    // 只有选了「自定义」才展开日期按钮 —— 这就是"不常驻"
                    if (customSelected) {
                        val range = vm.timeFilter as TimeFilter.Custom
                        Spacer(Modifier.height(6.dp))
                        DateButton("起始", range.startMillis) { pickerTarget = 1 }
                        Spacer(Modifier.height(6.dp))
                        DateButton("结束", range.endMillis) { pickerTarget = 2 }
                    }
                }

                // 中间细分隔线
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                // 右：类别
                Column(modifier = Modifier.weight(1f)) {
                    SectionLabel("类别")
                    OptionRow(
                        label = "不限",
                        selected = vm.categoryFilter == null,
                        onClick = { vm.onCategoryFilterChange(null) },
                    )
                    vm.activeCategories.forEach { c ->
                        OptionRow(
                            label = c.name,
                            selected = vm.categoryFilter == c.id,
                            dotColor = Color(c.colorArgb),
                            onClick = { vm.onCategoryFilterChange(c.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            // ── 排序（补的那一栏）──
            SectionLabel("排序")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortOrder.entries.forEach { order ->
                    FilterChip(
                        selected = vm.sortOrder == order,
                        onClick = { vm.onSortOrderChange(order) },
                        label = { Text(order.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 底部 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.resetFilters() }) { Text("重置") }
                Button(onClick = onDismiss, shape = MaterialTheme.shapes.large) { Text("完成") }
            }
        }
    }

    // ── 官方日历弹窗 ──
    if (pickerTarget != 0) {
        val isStart = pickerTarget == 1
        val lowerUtc = vm.customRangeLowerBound.toLocalDate().toUtcMidnightMillis()
        val upperUtc = LocalDate.now().toUtcMidnightMillis()
        val initialLocal = vm.timeFilter.let {
            if (it is TimeFilter.Custom) {
                if (isStart) it.startMillis else it.endMillis
            } else {
                if (isStart) vm.customRangeLowerBound else vm.customRangeUpperBound
            }
        }

        // ⭐ 把"最早=第一笔记账、最晚=今天"这个约束**做进组件**
        //    而不是等用户选完再弹一句"你选错了" —— 用户根本点不出无效日期
        val selectable = remember(lowerUtc, upperUtc) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis in lowerUtc..upperUtc

                override fun isSelectableYear(year: Int) =
                    year in LocalDate.now().year - 100..LocalDate.now().year
            }
        }

        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialLocal.toLocalDate().toUtcMidnightMillis(),
            selectableDates = selectable,
        )

        DatePickerDialog(
            onDismissRequest = { pickerTarget = 0 },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utc ->
                        if (isStart) {
                            vm.onCustomStartChanged(utcMidnightToLocalDay(utc, startOfDay = true))
                        } else {
                            vm.onCustomEndChanged(utcMidnightToLocalDay(utc, startOfDay = false))
                        }
                    }
                    pickerTarget = 0
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pickerTarget = 0 }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
    )
}

/**
 * 一行可选项。
 * dotColor 不为 null 时左边画一个色点（类别用），跟列表里的色点呼应。
 */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    dotColor: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (selected) {
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun DateButton(label: String, millis: Long, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = millis.toLocalDate().toShortDateText(),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}
