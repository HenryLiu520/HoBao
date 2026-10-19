package com.liuhaoran.hobao.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.util.flagEmoji
import java.util.Locale

/**
 * 货币选择弹层。
 *
 * 结构：一行一个币种 —— 国旗 + 代码/中文名，下面一行写汇率，最右边一个单选点。
 * 汇率没填的币种**灰掉不可选**（选了也记不成账，不如根本不让选）。
 *
 * 为什么用底部弹层而不是下拉菜单：
 * 每行要放"国旗 + 代码 + 中文名 + 汇率 + 单选点"，信息量比下拉菜单一行能装的多，
 * 而且底部弹层离拇指近，单手好按。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyPickerSheet(
    currencies: List<CurrencyEntity>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                text = "选择货币",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = "汇率都在设置 → 汇率里填。没填汇率的币种记不了账。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            )
            Spacer(modifier = Modifier.padding(top = 8.dp))

            currencies.forEach { c ->
                CurrencyPickRow(
                    currency = c,
                    selected = c.code == selectedCode,
                    onClick = {
                        onSelect(c.code)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun CurrencyPickRow(
    currency: CurrencyEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 汇率没填的币种不能记账，所以这里也不让选
    val usable = currency.isBase || currency.ratePerCny > 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = usable, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .then(if (usable) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = flagEmoji(currency.code), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "${currency.code}  ${currency.nameZh}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    // 人民币是账本基准，汇率恒为 1
                    currency.isBase -> "账本基准"
                    currency.ratePerCny > 0.0 ->
                        "1 CNY = ${formatRate(currency.ratePerCny)} ${currency.code}"
                    else -> "未设置汇率，不能记账"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!currency.isBase && currency.ratePerCny <= 0.0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,          // 整行都可点，点不用单独响应
            enabled = usable,
        )
    }
}

/** 汇率显示：固定两位小数（存储精度就是两位） */
private fun formatRate(rate: Double): String = String.format(Locale.US, "%.2f", rate)
