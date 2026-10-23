package com.quantpilot.app.data

import java.time.LocalDate

/** 日K线。price 为前复权价。 */
data class Bar(
    val date: LocalDate,
    val open: Float,
    val close: Float,
    val high: Float,
    val low: Float,
    val volume: Float,
)

/** 实时报价。 */
data class Quote(
    val code: String,
    val name: String,
    val price: Float,
    val prevClose: Float,
    val open: Float,
    val high: Float,
    val low: Float,
    val changePct: Float,
) {
    val isTrading: Boolean get() = price > 0f
}

/** 股票代码归一化：返回腾讯接口格式（sh600519 / sz000001 / hk01810）。 */
object Codes {
    fun normalize(input: String): String {
        val s = input.trim().lowercase().replace(".", "").removePrefix("hk")
        return when {
            s.length == 5 && s.all { it.isDigit() } -> "hk$s"
            s.length == 6 && s.all { it.isDigit() } ->
                (if (s.startsWith("6") || s.startsWith("9")) "sh" else "sz") + s
            else -> throw IllegalArgumentException("无法识别的代码: $input（A股6位数字或港股5位数字）")
        }
    }

    /** 展示用：hk01810 → 港股01810。 */
    fun display(tencentCode: String): String = when {
        tencentCode.startsWith("hk") -> "港股" + tencentCode.drop(2)
        else -> tencentCode
    }
}
