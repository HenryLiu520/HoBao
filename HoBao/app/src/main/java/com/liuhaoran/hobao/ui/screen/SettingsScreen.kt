package com.liuhaoran.hobao.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.util.flagEmoji
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel
import com.liuhaoran.hobao.widget.HoBaoWidgetProvider

/**
 * 界面三「设置」。
 *
 * 现在只有两项是真的：报告币种、汇率。
 * 其余仍是占位 —— 不藏起来，因为用户看到"有这个功能"比"以为没有"好。
 */
@Composable
fun SettingsScreen(
    vm: ExpenseViewModel,
    onOpenRates: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showReportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val widgetManager = remember { AppWidgetManager.getInstance(context) }
    // 不是所有桌面都支持"应用请求把自己钉到桌面"（Pixel/三星都支持）
    val canPinWidget = remember { widgetManager.isRequestPinAppWidgetSupported }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingRow(
                    title = "报告币种",
                    subtitle = "${flagEmoji(vm.reportCurrency.code)} ${vm.reportCurrency.code}" +
                        " · 只改显示，不改账本",
                    onClick = { showReportDialog = true },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    title = "汇率",
                    subtitle = "手动填写 1 CNY = 多少外币",
                    onClick = onOpenRates,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow("分类管理", "新建 / 改名 / 归档（还没做）", onClick = null)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow("数据备份", "导出 / 导入 JSON 备份", onClick = onOpenBackup)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    title = "桌面组件",
                    subtitle = if (canPinWidget) "四宫格：今日开销 + 打开 + 新建"
                    else "这个桌面不支持一键添加，请长按桌面空白处手动添加",
                    onClick = if (canPinWidget) {
                        {
                            widgetManager.requestPinAppWidget(
                                ComponentName(context, HoBaoWidgetProvider::class.java),
                                null,
                                null,
                            )
                        }
                    } else null,
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingRow("关于荷包", "版本 0.3", onClick = null)
            }
        }

        Text(
            text = "账本锚点固定是 CNY。切报告币种只会按今天的汇率重新折算显示，" +
                "已经记好的账本身一分钱都不会变。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    if (showReportDialog) {
        ReportCurrencyDialog(
            vm = vm,
            onDismiss = { showReportDialog = false },
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (onClick == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReportCurrencyDialog(vm: ExpenseViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("报告币种") },
        text = {
            Column {
                Text(
                    text = "切换后，总额会按今天的汇率重新折算。已经记好的账单本身金额不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                vm.currencies.sortedBy { it.sortOrder }.forEach { c ->
                    // 汇率没填的币种不能当报告币种 —— 否则全部折成 0
                    val usable = c.isBase || c.ratePerCny > 0.0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (usable) Modifier.clickable {
                                    vm.onReportCurrencyChange(c.code)
                                    onDismiss()
                                } else Modifier
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = c.code == vm.reportCurrencyCode,
                            onClick = if (usable) {
                                { vm.onReportCurrencyChange(c.code); onDismiss() }
                            } else null,
                            enabled = usable,
                        )
                        Text(
                            text = "${flagEmoji(c.code)} ${c.nameZh}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (usable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!usable) {
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "汇率未填",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
