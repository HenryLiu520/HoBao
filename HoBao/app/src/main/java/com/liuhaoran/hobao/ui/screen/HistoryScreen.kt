package com.liuhaoran.hobao.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.R
import com.liuhaoran.hobao.ui.component.ExpenseRowItem
import com.liuhaoran.hobao.ui.component.FilterSheet
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel
import com.liuhaoran.hobao.viewmodel.MonthGroup

/**
 * 界面二「回顾」
 *
 * 顶部从原来的"三行标签"改成：**搜索框 + 右侧漏斗按钮**。
 * 所有筛选（时间 / 类别 / 排序）都收进那个漏斗弹出的面板里。
 *
 * 代价（你自己接受的取舍）：看"某个月"从 1 次点击变成 4~5 次。
 * 换来的是列表能多显示两条记录、页面顶部只剩一行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: ExpenseViewModel,
    modifier: Modifier = Modifier,
) {
    var showFilter by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val visible = vm.visibleItems
    val total = visible.sumOf { it.cnyCents }
    val groups: List<MonthGroup> =
        if (vm.sortOrder.groupsByMonth) vm.groupByMonth(visible) else emptyList()

    Column(modifier = modifier.fillMaxSize()) {

        // ── 搜索 + 筛选入口 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("搜索原因…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = { showFilter = true },
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_filter),
                    contentDescription = "筛选",
                )
            }
        }

        // ── 当前筛选摘要 ──
        // 没有这一行的话，用户关掉面板后会忘了自己在看筛选后的数据，
        // 然后怀疑"我的账怎么少了"。
        vm.filterSummary?.let { summary ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "筛选：$summary",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.resetFilters() }) { Text("清除") }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 汇总卡 ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "合计支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = vm.reportText(total),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = "${visible.size} 笔",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 列表 ──
        if (visible.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        vm.query.isNotBlank() -> "没有匹配「${vm.query}」的记录"
                        vm.filterSummary != null -> "这个筛选条件下没有记录"
                        else -> "这里还没有记录"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (groups.isEmpty()) {
                    items(visible, key = { it.expense.id }) { item ->
                        ExpenseRowItem(
                            item = item,
                            reportCurrency = vm.reportCurrency,
                            onDelete = { vm.deleteExpense(item.expense) },
                        )
                    }
                } else {
                    groups.forEach { group ->
                        item(key = "header-${group.label}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = group.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = vm.reportText(group.subtotalCnyCents),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        items(group.items, key = { it.expense.id }) { item ->
                            ExpenseRowItem(
                                item = item,
                                reportCurrency = vm.reportCurrency,
                                onDelete = { vm.deleteExpense(item.expense) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilter) {
        FilterSheet(vm = vm, onDismiss = { showFilter = false })
    }
}
