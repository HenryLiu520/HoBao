package com.liuhaoran.hobao.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuhaoran.hobao.data.backup.ImportMode
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 二级页面「数据备份」—— 导出 / 导入 JSON。
 *
 * 【文件读写为什么不上权限】
 * 用的是官方的 SAF（Storage Access Framework）：
 *   导出 → `ActivityResultContracts.CreateDocument`，系统弹"存到哪"
 *   导入 → `ActivityResultContracts.OpenDocument`，系统弹"选哪个"
 * 用户选的目录/文件被系统授权给本 app，我们只拿一个 uri。
 * 所以清单里**一条存储权限都不用申请** —— WRITE_EXTERNAL_STORAGE 从 Android 10 起已经废了。
 *
 * 【为什么 MIME 不用 application/json，而用通配】
 * 某些文件管理器/网盘把 .json 报成 text/plain 或 application/octet-stream，
 * 限定成 application/json 会导致文件在选择器里是灰的、选不中。
 * 所以放开选，靠读出来的内容判断是不是荷包的备份（BackupCodec 会检查 format 字段）。
 *
 * ⚠️ 这段注释里千万别写那个通配 MIME 的字面量 ——
 *    Kotlin 的块注释是**可以嵌套**的，里面的 "斜杠+星号" 会再开一层注释，
 *    结果把后面整段代码吞掉，报错却指向几百行之后的某个括号（极难定位）。
 */
@Composable
fun DataTransferScreen(
    vm: ExpenseViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mergeMode by rememberSaveable { mutableStateOf(true) }
    var showReplaceConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    // ── 导出 ──
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val text = vm.buildBackupText()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入这个位置")
                }
            }.onSuccess {
                snackbarHostState.showSnackbar("备份已导出")
            }.onFailure {
                snackbarHostState.showSnackbar("导出失败：${it.message}")
            }
            busy = false
        }
    }

    // ── 导入 ──
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取这个文件")
                }
                vm.importBackupText(
                    text = text,
                    mode = if (mergeMode) ImportMode.MERGE else ImportMode.REPLACE,
                )
            }.onSuccess { result ->
                lastResult = result.summary()
                snackbarHostState.showSnackbar(result.summary())
            }.onFailure { e ->
                lastResult = "导入失败：${e.message}"
                snackbarHostState.showSnackbar("导入失败：${e.message}")
            }
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "备份是一个 .json 文件，里面有全部账单、分类和汇率。可以存到云盘或发给自己。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // ── 导出 ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "导出备份",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "把现在全部数据存成一个文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        exportLauncher.launch("hobao-backup-${LocalDate.now()}.json")
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("导出到文件")
                }
            }
        }

        // ── 导入 ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "导入备份",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "先选导入方式，再选文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ModeRow(
                    title = "合并",
                    desc = "追加到现有数据，重复的自动跳过。安全。",
                    selected = mergeMode,
                    onClick = { mergeMode = true },
                )
                ModeRow(
                    title = "覆盖",
                    desc = "先清空全部数据再导入 —— 不可撤销。",
                    selected = !mergeMode,
                    onClick = { mergeMode = false },
                )

                Button(
                    onClick = {
                        if (mergeMode) {
                            importLauncher.launch(arrayOf("*/*"))
                        } else {
                            showReplaceConfirm = true
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("选择备份文件")
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        lastResult?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Text(
            text = "旧版本的备份也能导入 —— 文件里带着格式版本号，app 会自动升级它。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text("确定覆盖？") },
            text = {
                Text(
                    "这会先删掉现在所有的账单、分类和汇率，再用备份文件重建。" +
                        "删掉之后没法恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirm = false
                        importLauncher.launch(arrayOf("*/*"))
                    }
                ) {
                    Text("清空并导入", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModeRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
