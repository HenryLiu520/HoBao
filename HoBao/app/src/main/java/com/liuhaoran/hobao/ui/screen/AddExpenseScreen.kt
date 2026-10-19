package com.liuhaoran.hobao.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.model.toUtcMidnightMillis
import com.liuhaoran.hobao.model.utcMidnightToLocalDate
import com.liuhaoran.hobao.ui.component.CurrencyPickerSheet
import com.liuhaoran.hobao.ui.component.ExpenseRowItem
import com.liuhaoran.hobao.util.formatMoney
import com.liuhaoran.hobao.util.parseAmountToMinor
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 界面一「记一笔」
 *
 * 布局：两行两列 —— 金额/原因、货币/日期。
 * 四个框全部走同一个 FieldFrame，标签位置、边框、高度完全一致。
 *
 * （为什么不是一行四个：352dp 里塞四个框，每个只剩 82dp，
 *   `10月19日` 至少要 100dp 才不被截断。两行两列每个 172dp，舒展得多。）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    vm: ExpenseViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenRates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var reasonText by rememberSaveable { mutableStateOf("") }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickedUtcMillis by rememberSaveable { mutableLongStateOf(LocalDate.now().toUtcMidnightMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCurrencySheet by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val categories = vm.activeCategories
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
        ?: categories.firstOrNull()

    val entry = vm.entryCurrency
    val minorUnits = entry?.minorUnits ?: 2

    val pickedDate = pickedUtcMillis.utcMidnightToLocalDate()
    val isToday = pickedDate == LocalDate.now()

    val amountValid = entry != null && parseAmountToMinor(amountText, minorUnits)?.let { it > 0 } == true
    val showAmountError = amountText.isNotEmpty() && !amountValid
    val rateReady = vm.entryCurrencyUsable
    val canSave = amountValid && selectedCategory != null && rateReady

    // 「只能点、不能打字」的两个框共用这套颜色：禁用色覆盖成正常色
    val readOnlyColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledBorderColor = MaterialTheme.colorScheme.outline,
        disabledContainerColor = Color.Transparent,
        disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 本月概览 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "本月支出",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = vm.reportText(vm.thisMonthCnyCents),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "共 ${vm.thisMonthCount} 笔 · 按今日汇率折算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // ── 输入区：两行两列 ──
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 第一行：金额 / 原因
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FieldFrame(label = "金额", modifier = Modifier.weight(1f)) { m ->
                    // 前缀只在"已经有内容"时显示：空框就该看起来是空的
                    val amountPrefix: (@Composable () -> Unit)? = if (amountText.isEmpty()) null
                    else { { Text("${entry?.code ?: "CNY"} ") } }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        prefix = amountPrefix,
                        singleLine = true,
                        isError = showAmountError,
                        // 只在出错时才出现一行说明 —— 平时一个字都不显示
                        supportingText = if (showAmountError) {
                            { Text("格式不对") }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = m,
                    )
                }
                FieldFrame(label = "原因", modifier = Modifier.weight(1f)) { m ->
                    OutlinedTextField(
                        value = reasonText,
                        onValueChange = { reasonText = it },
                        singleLine = true,
                        modifier = m,
                    )
                }
            }

            // 第二行：货币 / 日期（两个都是"点开选"，不能打字）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FieldFrame(
                    label = "货币",
                    modifier = Modifier.weight(1f),
                    onClick = { showCurrencySheet = true },
                ) { m ->
                    OutlinedTextField(
                        value = entry?.code ?: "CNY",
                        onValueChange = {},
                        enabled = false,
                        singleLine = true,
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                            )
                        },
                        colors = readOnlyColors,
                        modifier = m,
                    )
                }
                FieldFrame(
                    label = "日期",
                    modifier = Modifier.weight(1f),
                    onClick = { showDatePicker = true },
                ) { m ->
                    OutlinedTextField(
                        value = pickedDate.toDayLabel(),
                        onValueChange = {},
                        enabled = false,
                        singleLine = true,
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.DateRange,
                                contentDescription = null,
                            )
                        },
                        colors = readOnlyColors,
                        modifier = m,
                    )
                }
            }

            // ── 汇率没填：拦住，并给一条出路 ──
            if (!rateReady && entry != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${entry.code} 的汇率还没填",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "填好汇率之前，记不了这个币种的账。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = onOpenRates) { Text("去填写") }
                    }
                }
            }

            // ── 分类 ──
            when {
                !vm.categoriesLoaded -> Unit

                categories.isEmpty() -> Text(
                    text = "还没有分类。去 设置 → 分类管理 建一个，才能开始记账。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = c.id == selectedCategory?.id,
                            onClick = { selectedCategoryId = c.id },
                            label = { Text(c.name) },
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val cur = entry ?: return@Button
                    val cents = parseAmountToMinor(amountText, cur.minorUnits) ?: return@Button
                    val cat = selectedCategory ?: return@Button
                    vm.addExpense(
                        amountMinor = cents,
                        reason = reasonText,
                        categoryId = cat.id,
                        createdAt = createdAtFor(pickedDate),
                    )
                    amountText = ""
                    reasonText = ""
                    // ⚠️ 日期必须复位：不然补记一笔上个月的账之后，
                    // 接着记的下一笔会被默默记到上个月
                    pickedUtcMillis = LocalDate.now().toUtcMidnightMillis()
                    focusManager.clearFocus()
                    keyboard?.hide()
                    val whenText = if (isToday) "" else " · ${pickedDate.toDayLabel()}"
                    scope.launch {
                        snackbarHostState.showSnackbar("已记下 ${formatMoney(cents, cur)}$whenText")
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text("记一笔", style = MaterialTheme.typography.titleMedium)
            }
        }

        // ── 最近 3 笔 ──
        if (vm.recentItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "最近记的",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                vm.recentItems.forEach { item ->
                    ExpenseRowItem(item = item, reportCurrency = vm.reportCurrency)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showCurrencySheet) {
        CurrencyPickerSheet(
            currencies = vm.entryCurrencies,
            selectedCode = vm.entryCurrencyCode,
            onSelect = { vm.onEntryCurrencyChange(it) },
            onDismiss = { showCurrencySheet = false },
        )
    }

    // ── 官方日期选择器 ──
    if (showDatePicker) {
        val todayUtc = LocalDate.now().toUtcMidnightMillis()
        val maxYear = LocalDate.now().year
        // 用约束消除无效状态：未来的日期在日历上是灰的、点不动
        val selectable = remember(todayUtc, maxYear) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= todayUtc

                override fun isSelectableYear(year: Int): Boolean =
                    year in 2000..maxYear
            }
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = pickedUtcMillis,
            selectableDates = selectable,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { pickedUtcMillis = it }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private val LABEL_LIFT = 8.dp

/**
 * 四个输入框共用的外壳 —— **标签永远画在左上角的边框上**。
 *
 * 做法：TextField 顶部留 8dp（边框线正好落在 y=8dp），
 *      标签画在 y=0 起、字高约 16dp → 垂直中线正好 8dp，压在边框线上；
 *      标签带一块背景色，把底下的边框线遮掉，做出"标签骑在线上开了个缺口"的效果。
 *
 * 为什么不用 TextField 自带的 label：它会在"空且未聚焦"时把标签缩进框内居中，
 * 有值了才飘到边框上 —— 几个框并排就会不齐。
 *
 * 传 onClick 它就变成可点的容器（货币框、日期框用这个），不传就是普通容器。
 */
@Composable
private fun FieldFrame(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    field: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
    ) {
        field(
            Modifier
                .fillMaxWidth()
                .padding(top = LABEL_LIFT)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 12.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 4.dp),
        )
    }
}

/**
 * 把"用户选的日期"变成真正的时间戳。
 *
 * 【为什么保留当前的时分秒，而不是用那天 00:00】
 * 1. 同一天里，后记的账应该排在前面（按你实际录入的顺序），00:00 会让顺序乱掉
 * 2. 用 00:00 还可能踩时区坑：某些时区在午夜有夏令时切换
 *
 * 选的就是今天 → 直接用"现在"，连毫秒都是真实的记账时刻。
 */
private fun createdAtFor(picked: LocalDate): Long =
    if (picked == LocalDate.now()) {
        System.currentTimeMillis()
    } else {
        picked.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

/** LocalDate → "10月5日" */
private fun LocalDate.toDayLabel(): String = "${monthValue}月${dayOfMonth}日"
