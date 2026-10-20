package com.liuhaoran.hobao.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.liuhaoran.hobao.ui.navigation.Destination
import com.liuhaoran.hobao.ui.screen.AddExpenseScreen
import com.liuhaoran.hobao.ui.screen.AnalysisScreen
import com.liuhaoran.hobao.ui.screen.DataTransferScreen
import com.liuhaoran.hobao.ui.screen.HistoryScreen
import com.liuhaoran.hobao.ui.screen.RatesScreen
import com.liuhaoran.hobao.ui.screen.SettingsScreen
import com.liuhaoran.hobao.viewmodel.ExpenseViewModel

/** 二级页面路由（不在底部页签里） */
private const val ROUTE_RATES = "rates"
private const val ROUTE_BACKUP = "backup"

/**
 * 二级页面：路由 → 标题。
 * 不在这个表里的就是一级页（有底部导航栏、没有返回箭头）。
 * 把"哪一页是二级页"集中成一张表，比在每个地方写 if 判断更不容易漏。
 */
private val SECONDARY_TITLES = mapOf(
    ROUTE_RATES to "汇率",
    ROUTE_BACKUP to "数据备份",
)

/**
 * app 的外壳：顶部标题栏 + 底部页签 + 中间的页面容器。
 *
 * 【两个层级】
 * 一级页：四个页签（记一笔 / 回顾 / 分析 / 设置）—— 才有底部导航栏
 * 二级页：汇率 —— 从设置点进去，有返回箭头，**没有底部导航栏**
 *
 * 判断"现在是不是二级页"用 backStack 的当前路由，而不是另存一个布尔值 ——
 * 路由本身就是唯一真相，存两份状态迟早会不一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoBaoApp() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val vm: ExpenseViewModel = viewModel(factory = ExpenseViewModel.Factory)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val secondaryTitle = SECONDARY_TITLES[route]
    val isSecondary = secondaryTitle != null
    val current = Destination.fromRoute(route)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(secondaryTitle ?: current.label) },
                navigationIcon = {
                    if (isSecondary) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!isSecondary) {
                NavigationBar {
                    Destination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = current == dest,
                            onClick = {
                                if (current != dest) {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(dest.iconRes),
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Add.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Add.route) {
                AddExpenseScreen(
                    vm = vm,
                    snackbarHostState = snackbarHostState,
                    onOpenRates = { navController.navigate(ROUTE_RATES) },
                )
            }
            composable(Destination.History.route) {
                HistoryScreen(vm = vm)
            }
            composable(Destination.Analysis.route) {
                AnalysisScreen(vm = vm)
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    vm = vm,
                    onOpenRates = { navController.navigate(ROUTE_RATES) },
                    onOpenBackup = { navController.navigate(ROUTE_BACKUP) },
                )
            }
            composable(ROUTE_RATES) {
                RatesScreen(vm = vm)
            }
            composable(ROUTE_BACKUP) {
                DataTransferScreen(vm = vm, snackbarHostState = snackbarHostState)
            }
        }
    }
}
