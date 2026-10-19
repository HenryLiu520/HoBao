package com.liuhaoran.hobao.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.util.cnyMinorToMinor
import com.liuhaoran.hobao.util.formatMoney
import com.liuhaoran.hobao.util.toDayTimeText
import com.liuhaoran.hobao.viewmodel.ExpenseItem

/**
 * 单条账单。三处共用：「记一笔」的"最近记的"、「回顾」的列表。
 *
 * 【金额为什么显示两个】
 * 主金额 = 按**报告币种**折算后的值（随汇率变）—— 这是你现在想看的
 * 副金额 = 这笔记账时的**原币**金额（永远不变）—— 这是你能对上号的
 *
 * 只显示折算值会出问题：你在日本花 980 円 吃了碗拉面，界面显示 "¥47.12"，
 * 回头你根本想不起 47 块是什么。原币那行就是"我记得的是这个数"的锚。
 */
@Composable
fun ExpenseRowItem(
    item: ExpenseItem,
    reportCurrency: CurrencyEntity,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rate = if (reportCurrency.ratePerCny > 0.0) reportCurrency.ratePerCny else 1.0
    val mainMinor = cnyMinorToMinor(item.cnyCents, reportCurrency.minorUnits, rate)
    val showOriginal = item.expense.currencyCode != reportCurrency.code

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分类色点：颜色存在数据库的 categories.colorArgb 里
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(item.category.colorArgb))
            )
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.expense.reason,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = item.category.name + " · " + item.expense.createdAt.toDayTimeText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.padding(start = 8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = formatMoney(mainMinor, reportCurrency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                )
                if (showOriginal) {
                    Text(
                        text = formatMoney(item.expense.amountMinor, item.currency),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除这条记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
