package com.liuhaoran.hobao.data.forex

/**
 * 汇率数据源的**全部配置**。
 *
 * 【为什么都放这里】
 * 「base 用哪个币种」「支持哪几个币」「超时几秒」「要不要降级」——
 * 这些是产品决策，不是某段代码的实现细节。
 * 散落在调用点的话，改一个 base 要全文搜；放在一处，一眼能看完。
 *
 * 数据源：fawazahmed0/currency-api（无 key、免费、任意基准、支持历史日期）
 * 实测笔记：~/.hermes/knowledge/forex-api/README.md
 */
object ForexConfig {

    /**
     * 账本锚点 = 人民币。
     *
     * 【为什么 base 必须是 cny，而不是 usd】
     * 本项目的核心字段 `ratePerCny` 语义是「1 CNY = 多少外币」。
     * 拿 CNY 当 base 时，响应里的 `cny.usd` **直接就是** USD 的 ratePerCny ——
     * 一次换算都不需要，也就没有精度损失和方向搞反的风险。
     */
    const val BASE: String = "cny"

    /** 法币白名单。响应里有 339 个键，混着 1inch / aave 这类 token，绝不整表往下传 */
    val SUPPORTED: List<String> = listOf("CNY", "HKD", "JPY", "USD", "EUR", "GBP")

    /**
     * 数据源，按顺序尝试。
     *
     * 1. pages.dev —— 响应头 `max-age=0, must-revalidate`，数据新鲜
     * 2. jsDelivr —— 有 CDN 长缓存（实测 age 6.7 小时），只当兜底
     */
    val SOURCES: List<String> = listOf(
        "https://latest.currency-api.pages.dev/v1/currencies/%s.json",
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/%s.json",
    )

    /** 主源失败时要不要自动换备用源 */
    const val FALLBACK_ENABLED: Boolean = true

    /** 单次请求超时（连接 + 读取各 10 秒） */
    const val TIMEOUT_MS: Int = 10_000

    /**
     * 进程内重复请求的冷却时间。
     *
     * 数据源是一天一更，所以「同一天只请求一次」等价于一个够长的冷却窗。
     * 进程被系统杀掉后内存没了也没关系 —— 落盘缓存 + WorkManager 每天一次兜住。
     */
    const val IN_PROCESS_COOLDOWN_MS: Long = 6 * 60 * 60 * 1000L

    /** 每日后台同步的间隔 */
    const val SYNC_PERIOD_HOURS: Long = 24L

    /** WorkManager 里的唯一任务名（同一个名字重复入队不会叠加） */
    const val WORK_NAME: String = "hobao-forex-daily"

    /** 源给的 date 一定是 YYYY-MM-DD，用它做缓存 key */
    val DATE_REGEX: Regex = Regex("""^\d{4}-\d{2}-\d{2}$""")

    fun isSupported(code: String): Boolean = SUPPORTED.any { it.equals(code, ignoreCase = true) }
}
