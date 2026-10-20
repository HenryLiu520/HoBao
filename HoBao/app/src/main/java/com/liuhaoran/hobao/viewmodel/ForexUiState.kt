package com.liuhaoran.hobao.viewmodel

/**
 * 「自动联网更新」在界面上的四种状态。
 *
 * 特意区分 Idle / Syncing / Done(stale) / Error，
 * 因为这一块最怕的就是「看起来成功、其实是旧数据」——
 * 记账 app 拿旧汇率算钱，比报个错危险得多。
 */
sealed interface ForexUiState {
    data object Idle : ForexUiState
    data object Syncing : ForexUiState
    /** stale = 联网失败，用的是上一次的缓存 */
    data class Done(val stale: Boolean) : ForexUiState
    data class Error(val message: String) : ForexUiState
}
