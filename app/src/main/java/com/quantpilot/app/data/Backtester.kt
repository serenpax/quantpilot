package com.quantpilot.app.data

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 回测引擎：与 quant_pilot(Python) 同逻辑。
 * T 日收盘算仓位 → T+1 日开盘成交；单边佣金万3 + 滑点0.1%。
 */
object Backtester {

    data class Result(
        val dates: List<String>,
        val closes: FloatArray,      // 前复权收盘
        val positions: FloatArray,
        val equity: FloatArray,      // 策略净值
        val benchmark: FloatArray,   // 买入持有净值
        val stats: Stats,
    )

    data class Stats(
        val totalReturn: Float,      // %
        val annualReturn: Float,
        val benchmarkReturn: Float,
        val maxDrawdown: Float,      // 负数 %
        val sharpe: Float,
        val nTrades: Int,
        val winRate: Float?,         // %
        val maxWin: Float?,          // 单笔最大盈利 %
        val maxLoss: Float?,         // 单笔最大亏损 %
        val avgWin: Float?,
        val avgLoss: Float?,
    )

    fun run(bars: List<Bar>, strategyKey: String, fee: Float = 0.0003f, slippage: Float = 0.001f): Result {
        require(bars.size >= 60) { "K线太少(${bars.size}根)，至少需要60根" }
        val n = bars.size
        val pos = Strategies.position(strategyKey, bars)
        val equity = FloatArray(n)
        val benchmark = FloatArray(n)
        equity[0] = 1f
        benchmark[0] = 1f
        for (i in 1 until n) {
            val change = bars[i].close / bars[i - 1].close - 1f
            val prevPos = pos[i - 1]
            val turnover = kotlin.math.abs(pos[i] - prevPos)
            val cost = if (pos[i] > prevPos) (fee + slippage) * pos[i] else 0f
            // 卖出时按减仓比例收手续费
            val sellCost = if (pos[i] < prevPos) (fee + slippage) * (prevPos - pos[i]) else 0f
            equity[i] = equity[i - 1] * (1f + prevPos * change - cost - sellCost)
            benchmark[i] = benchmark[i - 1] * (1f + change)
            @Suppress("UNUSED_EXPRESSION") turnover
        }

        val total = equity[n - 1] - 1f
        val annual = (1f + total).pow(244f / n) - 1f
        var sumRet = 0f
        var sumSq = 0f
        for (i in 1 until n) {
            val r = equity[i] / equity[i - 1] - 1f
            sumRet += r
            sumSq += r * r
        }
        val mean = sumRet / (n - 1)
        val vol = sqrt((sumSq / (n - 1) - mean * mean).coerceAtLeast(0f)) * sqrt(244f)
        val sharpe = if (vol > 0f) annual / vol else 0f

        var peak = equity[0]
        var dd = 0f
        for (v in equity) {
            if (v > peak) peak = v
            val cur = v / peak - 1f
            if (cur < dd) dd = cur
        }

        // 单笔统计：空仓→持仓为一笔开始，持仓→空仓为结束
        val trades = mutableListOf<Float>()
        var entryEquity = -1f
        for (i in 1 until n) {
            if (pos[i] > 0f && pos[i - 1] == 0f) entryEquity = equity[i - 1]
            else if (pos[i] == 0f && pos[i - 1] > 0f && entryEquity > 0f) {
                trades.add(equity[i - 1] / entryEquity - 1f)
                entryEquity = -1f
            }
        }
        val wins = trades.filter { it > 0f }
        val losses = trades.filter { it <= 0f }

        return Result(
            dates = bars.map { it.date.toString() },
            closes = FloatArray(n) { bars[it].close },
            positions = pos,
            equity = equity,
            benchmark = benchmark,
            stats = Stats(
                totalReturn = total * 100f,
                annualReturn = annual * 100f,
                benchmarkReturn = (benchmark[n - 1] - 1f) * 100f,
                maxDrawdown = dd * 100f,
                sharpe = sharpe,
                nTrades = trades.size,
                winRate = if (trades.isEmpty()) null else wins.size.toFloat() / trades.size * 100f,
                maxWin = trades.maxOrNull()?.let { it * 100f },
                maxLoss = trades.minOrNull()?.let { it * 100f },
                avgWin = if (wins.isEmpty()) null else wins.average().toFloat() * 100f,
                avgLoss = if (losses.isEmpty()) null else losses.average().toFloat() * 100f,
            ),
        )
    }
}
