package com.quantpilot.app.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 策略层：与 quant_pilot(Python) 同款 8 个策略。
 * 输入日K序列，输出同长度仓位数组（0~1；单策略 0/1，组合策略可为小数）。
 * 仓位语义：T 日收盘算出的仓位在 T+1 生效（回测引擎会 shift）。
 */
object Strategies {

    val NAMES: Map<String, String> = linkedMapOf(
        "sma_cross" to "双均线",
        "macd" to "MACD",
        "rsi" to "RSI超卖",
        "turtle" to "海龟突破",
        "bollinger" to "布林带回归",
        "atr_turtle" to "ATR海龟",
        "false_breakout" to "假突破反转",
        "regime_switch" to "体制切换组合",
        "ensemble" to "投票平均组合",
        "diversified" to "四维分散组合",
    )

    fun position(key: String, bars: List<Bar>): FloatArray = when (key) {
        "sma_cross" -> smaCross(bars)
        "macd" -> macd(bars)
        "rsi" -> rsiRebound(bars)
        "turtle" -> turtle(bars)
        "bollinger" -> bollinger(bars)
        "atr_turtle" -> atrTurtle(bars)
        "false_breakout" -> falseBreakout(bars)
        "regime_switch" -> regimeSwitch(bars)
        "ensemble" -> ensemble(bars)
        "diversified" -> diversified(bars)
        else -> throw IllegalArgumentException("未知策略: $key")
    }

    /** 今日信号：仓位较昨日上升=买入，下降=卖出，持平=持有。 */
    fun signal(key: String, bars: List<Bar>): Signal {
        val pos = position(key, bars)
        if (bars.size < 2) return Signal.HOLD
        return when {
            pos[pos.size - 1] > pos[pos.size - 2] -> Signal.BUY
            pos[pos.size - 1] < pos[pos.size - 2] -> Signal.SELL
            else -> Signal.HOLD
        }
    }

    /**
     * 盘中信号：把实时价当作"假定今日收盘价"合成一根今日K线，接到历史日K后算信号。
     * 得到的是"如果此刻收盘会给出的信号"，价格波动时会实时变化。
     * 若 quote 当日已在 bars 末尾（同一交易日），替换而非追加，避免重复计日。
     */
    fun intradaySignal(key: String, bars: List<Bar>, quote: Quote): Signal {
        if (bars.isEmpty() || !quote.isTrading) return signal(key, bars)
        val today = java.time.LocalDate.now()
        val synthetic = Bar(
            date = today,
            open = if (quote.open > 0f) quote.open else quote.price,
            close = quote.price,
            high = maxOf(quote.high, quote.price),
            low = if (quote.low > 0f) minOf(quote.low, quote.price) else quote.price,
            volume = 0f,
        )
        val merged = if (bars.last().date == today) bars.dropLast(1) + synthetic
        else bars + synthetic
        return signal(key, merged)
    }

    enum class Signal { BUY, SELL, HOLD }

    // ---------- 单策略 ----------

    private fun smaCross(bars: List<Bar>, fast: Int = 10, slow: Int = 30): FloatArray {
        val closes = bars.closes()
        val out = FloatArray(bars.size)
        var fastMa = 0f
        var slowMa = 0f
        var fastSum = 0f
        var slowSum = 0f
        for (i in bars.indices) {
            fastSum += closes[i]
            slowSum += closes[i]
            if (i >= fast) fastSum -= closes[i - fast]
            if (i >= slow) slowSum -= closes[i - slow]
            if (i >= slow - 1) {
                fastMa = fastSum / fast
                slowMa = slowSum / slow
                out[i] = if (fastMa > slowMa) 1f else 0f
            }
        }
        return out
    }

    private fun ema(values: FloatArray, span: Int): FloatArray {
        val k = 2f / (span + 1)
        val out = FloatArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) out[i] = values[i] * k + out[i - 1] * (1 - k)
        return out
    }

    private fun macd(bars: List<Bar>): FloatArray {
        val closes = bars.closes()
        val ema12 = ema(closes, 12)
        val ema26 = ema(closes, 26)
        val dif = FloatArray(bars.size) { ema12[it] - ema26[it] }
        val dea = ema(dif, 9)
        return FloatArray(bars.size) { if (dif[it] > dea[it]) 1f else 0f }
    }

    private fun rsiRebound(bars: List<Bar>, period: Int = 14, oversold: Float = 30f, overbought: Float = 70f): FloatArray {
        val closes = bars.closes()
        val out = FloatArray(bars.size)
        var avgGain = 0f
        var avgLoss = 0f
        var holding = 0f
        for (i in 1 until bars.size) {
            val d = closes[i] - closes[i - 1]
            val gain = max(d, 0f)
            val loss = max(-d, 0f)
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            val rsi = if (avgLoss <= 1e-12f) 100f else 100f - 100f / (1f + avgGain / avgLoss)
            if (holding == 0f && rsi < oversold) holding = 1f
            else if (holding == 1f && rsi > overbought) holding = 0f
            out[i] = holding
        }
        return out
    }

    private fun turtle(bars: List<Bar>, entry: Int = 20, exit: Int = 10): FloatArray {
        val closes = bars.closes()
        val out = FloatArray(bars.size)
        var holding = 0f
        for (i in bars.indices) {
            if (i < entry) continue
            if (holding == 0f && closes[i] > maxInRange(closes, i - entry, i)) holding = 1f
            else if (holding == 1f && closes[i] < minInRange(closes, i - exit, i)) holding = 0f
            out[i] = holding
        }
        return out
    }

    private fun bollinger(bars: List<Bar>, period: Int = 20, nStd: Float = 2f): FloatArray {
        val closes = bars.closes()
        val out = FloatArray(bars.size)
        var holding = 0f
        for (i in bars.indices) {
            if (i < period - 1) continue
            var sum = 0f
            for (j in i - period + 1..i) sum += closes[j]
            val mid = sum / period
            var sq = 0f
            for (j in i - period + 1..i) sq += (closes[j] - mid) * (closes[j] - mid)
            val std = kotlin.math.sqrt(sq / period)
            if (holding == 0f && closes[i] < mid - nStd * std) holding = 1f
            else if (holding == 1f && closes[i] >= mid) holding = 0f
            out[i] = holding
        }
        return out
    }

    private fun atrOf(bars: List<Bar>, period: Int = 20): FloatArray {
        val out = FloatArray(bars.size)
        var atr = 0f
        for (i in bars.indices) {
            val prevClose = if (i > 0) bars[i - 1].close else bars[i].close
            val tr = max(bars[i].high - bars[i].low, max(abs(bars[i].high - prevClose), abs(bars[i].low - prevClose)))
            atr = if (i == 0) tr else (atr * (period - 1) + tr) / period
            out[i] = atr
        }
        return out
    }

    private fun atrTurtle(bars: List<Bar>, entry: Int = 20, exit: Int = 10): FloatArray {
        val closes = bars.closes()
        val atr = atrOf(bars)
        val out = FloatArray(bars.size)
        var holding = 0f
        val warmup = entry + 10
        for (i in bars.indices) {
            if (i < warmup) continue
            var atrSum = 0f
            for (j in i - 9..i) atrSum += atr[j]
            val volOk = atr[i] > atrSum / 10f
            if (holding == 0f && volOk && closes[i] > maxInRange(closes, i - entry, i)) holding = 1f
            else if (holding == 1f && closes[i] < minInRange(closes, i - exit, i)) holding = 0f
            out[i] = holding
        }
        return out
    }

    // ---------- 组合策略 ----------

    private fun regimeSwitch(bars: List<Bar>, lookback: Int = 20, threshold: Float = 0.02f): FloatArray {
        val closes = bars.closes()
        val trend = atrTurtle(bars)
        val chop = bollinger(bars)
        val out = FloatArray(bars.size)
        for (i in bars.indices) {
            if (i < lookback) { out[i] = chop[i]; continue }
            var path = 0f
            for (j in i - lookback + 1..i) path += abs(closes[j] - closes[j - 1])
            val net = abs(closes[i] - closes[i - lookback])
            val er = if (path <= 0f) 0f else net / path
            out[i] = if (er > threshold) trend[i] else chop[i]
        }
        return out
    }

    private fun ensemble(bars: List<Bar>): FloatArray {
        val a = atrTurtle(bars)
        val b = smaCross(bars)
        val c = bollinger(bars)
        return FloatArray(bars.size) { (a[it] + b[it] + c[it]) / 3f }
    }

    /**
     * 假突破反转：价格跌破 N 日低点后快速收回 → 买入，专门赚"假摔"。
     * 多头版：入场=昨日最低跌破支撑但今日收盘收回；出场=突破近期高点或止损。
     */
    private fun falseBreakout(bars: List<Bar>, lookback: Int = 20, exitLookback: Int = 10): FloatArray {
        val closes = bars.closes()
        val out = FloatArray(bars.size)
        var holding = 0f
        for (i in bars.indices) {
            if (i < lookback) continue
            val support = minInRange(closes, i - lookback, i)  // 前 N 日最低
            val resistance = maxInRange(closes, i - exitLookback, i)  // 前 M 日最高

            // 入场：昨日最低跌破 support，但今日收盘收回到 support 之上
            if (holding == 0f && i >= 1) {
                val prevLow = bars[i - 1].low
                val todayClose = bars[i].close
                if (prevLow < support && todayClose >= support) {
                    holding = 1f
                }
            }
            // 出场：突破近期高点
            else if (holding == 1f && closes[i] > resistance) {
                holding = 0f
            }
            out[i] = holding
        }
        return out
    }

    /**
     * 四维分散组合：等权纳入经济逻辑分散的四类策略，每日自动再平衡。
     * ATR 海龟(趋势) + 布林带(均值回归) + 假突破反转(反转) + RSI(超卖反弹)。
     */
    private fun diversified(bars: List<Bar>): FloatArray {
        val trend = atrTurtle(bars)
        val meanRev = bollinger(bars)
        val falseBreak = falseBreakout(bars)
        val oversold = rsiRebound(bars)
        return FloatArray(bars.size) { (trend[it] + meanRev[it] + falseBreak[it] + oversold[it]) / 4f }
    }

    // ---------- helpers ----------

    private fun List<Bar>.closes(): FloatArray = FloatArray(size) { this[it].close }

    private fun maxInRange(a: FloatArray, from: Int, until: Int): Float {
        var m = Float.NEGATIVE_INFINITY
        for (i in from until until) m = max(m, a[i])
        return m
    }

    private fun minInRange(a: FloatArray, from: Int, until: Int): Float {
        var m = Float.POSITIVE_INFINITY
        for (i in from until until) m = min(m, a[i])
        return m
    }
}
