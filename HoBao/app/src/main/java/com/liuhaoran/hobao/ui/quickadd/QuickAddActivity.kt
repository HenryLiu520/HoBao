package com.liuhaoran.hobao.ui.quickadd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.ui.theme.HoBaoTheme
import com.liuhaoran.hobao.util.parseAmountToMinor
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel

/**
 * 快速记账的「悬浮小窗」。
 *
 * 【为什么是个 Activity，而不是真正的 Dialog】
 * 它需要**输入法**。输入法属于 app 进程，只有 Activity 才调得动。
 * 组件（App Widget）跑在桌面进程，物理上调不出键盘 ——
 * 这也是为什么"在组件里直接打字"这条路根本走不通。
 *
 * 视觉上用「透明主题 + 半透明遮罩 + 居中卡片」，
 * 看起来就是盖在桌面上的一张卡片，用户感觉不到"跳进了另一个 app"。
 * 点遮罩 = 取消；点保存 = 立刻回到桌面。
 *
 * 【金额和原因用的是同一个键盘】
 * 都是系统的输入法。金额框 `KeyboardType.Decimal` 会直接给数字键盘，
 * 原因框给普通文字键盘 —— 不再自己画一个键盘。
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HoBaoTheme {
                QuickAddDialog(onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun QuickAddDialog(onDismiss: () -> Unit) {
    val vm: ExpenseViewModel = viewModel(factory = ExpenseViewModel.Factory)
    val focusManager = LocalFocusManager.current
    val amountFocus = remember { FocusRequester() }

    // 只有填了汇率的币种才能记账（和 app 里一致）
    val usableCurrencies = vm.currencies.filter { it.ratePerCny > 0.0 }

    var currencyCode by rememberSaveable { mutableStateOf(vm.entryCurrencyCode) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }

    val currency = usableCurrencies.firstOrNull { it.code == currencyCode }
        ?: usableCurrencies.firstOrNull()
    val amountMinor = currency?.let { parseAmountToMinor(amountText, it.minorUnits) } ?: 0L
    val canSave = amountMinor > 0 && categoryId != null && currency != null

    // 打开就把光标放进金额框 —— 快点：数字、选分类、保存
    LaunchedEffect(Unit) {
        runCatching { amountFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // 点卡片外面 = 取消。indication = null 去掉整屏水波纹
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() }
            // 键盘弹出时把卡片顶上去，不然会被输入法盖住
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 340.dp)
                // 空 lambda 用来「吞掉」点击 —— 否则点卡片内部也会触发外面的取消
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {

                // ── 标题 + 币种 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "记一笔",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    CurrencyChip(
                        code = currency?.code ?: "—",
                        currencies = usableCurrencies,
                        onPick = { currencyCode = it },
                    )
                }

                // ── 金额 ──
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw -> amountText = sanitizeAmount(raw) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .focusRequester(amountFocus),
                    placeholder = { Text("0", fontSize = 24.sp) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    prefix = currency?.let { { Text(it.code + "  ") } },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    // 「下一项」把焦点交给原因框，用户不用自己点
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
                    ),
                )

                // ── 原因 ──
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    placeholder = { Text("原因（留空用分类名）", fontSize = 13.sp) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                )

                // ── 分类 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.activeCategories.forEach { c ->
                        FilterChipLite(
                            label = c.name,
                            selected = c.id == categoryId,
                            onClick = { categoryId = c.id },
                        )
                    }
                }

                // ── 底部：清空（左） / 保存（右） ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            // 只清"你打进去的"：金额和原因。
                            // 币种和分类留着，接着记第二笔时少点两下。
                            amountText = ""
                            reason = ""
                            amountFocus.requestFocus()
                        },
                    ) { Text("清空") }

                    Spacer(Modifier.weight(1f))

                    FilledIconButton(
                        onClick = {
                            val cur = currency ?: return@FilledIconButton
                            val cents = parseAmountToMinor(amountText, cur.minorUnits)
                                ?: return@FilledIconButton
                            val cat = categoryId ?: return@FilledIconButton
                            // 规则（原因回退、汇率快照）全部复用 ViewModel，
                            // 不在这里抄一遍 —— 抄一遍迟早和 app 里的行为分叉
                            vm.onEntryCurrencyChange(cur.code)
                            vm.addExpense(
                                amountMinor = cents,
                                reason = reason,
                                categoryId = cat,
                                onSaved = onDismiss,   // 存完了才关窗，避免协程被取消
                            )
                        },
                        enabled = canSave,
                        shape = CircleShape,
                        modifier = Modifier.height(52.dp).width(52.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "保存")
                    }
                }
            }
        }
    }
}

/** 只留数字和**一个**小数点。真正的合法性由 parseAmountToMinor 判定（它会看币种小数位） */
private fun sanitizeAmount(raw: String): String {
    val sb = StringBuilder()
    var dotUsed = false
    for (ch in raw) {
        when {
            ch.isDigit() -> sb.append(ch)
            ch == '.' && !dotUsed -> {
                sb.append(ch)
                dotUsed = true
            }
        }
    }
    return sb.toString()
}

/** 币种选择：一个 chip + 下拉菜单。只列填了汇率的币种 */
@Composable
private fun CurrencyChip(
    code: String,
    currencies: List<CurrencyEntity>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(code, fontWeight = FontWeight.Medium) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "切换币种",
                    modifier = Modifier.height(18.dp),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            currencies.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.code}  ${c.nameZh}") },
                    onClick = {
                        onPick(c.code)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 简化版 FilterChip（不依赖实验性 API） */
@Composable
private fun FilterChipLite(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
