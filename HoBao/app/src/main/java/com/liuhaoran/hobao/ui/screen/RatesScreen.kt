package com.liuhaoran.hobao.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.util.flagEmoji
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel
import java.util.Locale

/**
 * 二级页面「汇率」。
 *
 * 结构：以人民币为基准，一行一个外币 —— 「1 人民币 = X 外币」
 * 最下面一个暂时不能点的「自动联网更新」（用禁用态诚实地表示"还没做"）。
 *
 * ⚠️ 这里改的是 currencies 表里的**当前汇率**。
 *    已有账单各自存着快照，一行都不会被动到。
 */
@Composable
fun RatesScreen(vm: ExpenseViewModel, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<CurrencyEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "以 CNY 为基准。填「1 CNY 能换多少」该币种。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // ── 外币列表 ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                vm.foreignCurrencies.forEachIndexed { index, c ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    RateRow(currency = c, onClick = { editing = c })
                }
            }
        }

        // ── 自动联网更新：还没做，用禁用态诚实表示 ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0.45f)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动联网更新",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "即将推出",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = false, onCheckedChange = null, enabled = false)
            }
        }

        Text(
            text = "改汇率不会影响已经记好的账 —— 每条账都存着记账当时的汇率快照。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    editing?.let { c ->
        RateEditDialog(
            currency = c,
            onDismiss = { editing = null },
            onSave = { rate ->
                vm.updateRate(c.code, rate)
                editing = null
            },
        )
    }
}

@Composable
private fun RateRow(currency: CurrencyEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = flagEmoji(currency.code), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.width(12.dp))
        // 固定两行：第一行是公式，第二行是中文名 / 状态。
        // 已设置和未设置的行**行高必须一致** —— 不然列表看起来参差不齐。
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "1 CNY = " +
                    if (currency.ratePerCny > 0.0) "${formatRate(currency.ratePerCny)} ${currency.code}"
                    else "—— ${currency.code}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (currency.ratePerCny > 0.0) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (currency.ratePerCny > 0.0) currency.nameZh
                else "${currency.nameZh} · 未设置，点这里填写",
                style = MaterialTheme.typography.bodySmall,
                color = if (currency.ratePerCny > 0.0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RateEditDialog(
    currency: CurrencyEntity,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var text by remember {
        mutableStateOf(if (currency.ratePerCny > 0.0) formatRate(currency.ratePerCny) else "")
    }
    val trimmed = text.trim()
    val parsed = trimmed.toDoubleOrNull()
    val valid = trimmed.matches(Regex("^\\d{1,6}(\\.\\d{1,2})?$")) && (parsed ?: 0.0) > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${flagEmoji(currency.code)} ${currency.nameZh} 汇率") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "1 CNY 能换多少${currency.nameZh}？",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("汇率") },
                    prefix = { Text("1 CNY =") },
                    suffix = { Text(currency.code) },
                    singleLine = true,
                    isError = trimmed.isNotEmpty() && !valid,
                    supportingText = {
                        Text(
                            if (trimmed.isNotEmpty() && !valid) "请填大于 0 的数字，最多两位小数"
                            else "最多两位小数"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 汇率显示：固定两位小数（存储精度就是两位） */
private fun formatRate(rate: Double): String = String.format(Locale.US, "%.2f", rate)
