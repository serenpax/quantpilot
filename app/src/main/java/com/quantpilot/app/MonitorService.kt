package com.quantpilot.app

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.quantpilot.app.data.Codes
import com.quantpilot.app.data.QuoteApi
import com.quantpilot.app.data.Strategies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 盘中实时信号监控。
 * 开市期间每分钟：拉实时价 → 合成今日K线算盘中信号 → 信号方向变化时推送通知。
 * 去抖：只在 BUY/SELL 之间切换时通知，同方向不重复，HOLD 不通知。
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    // 历史日K缓存，按代码；一个交易日内只拉一次，盘中复用
    private val barsCache = HashMap<String, Pair<LocalDate, List<com.quantpilot.app.data.Bar>>>()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("watchlist", MODE_PRIVATE)
        SignalNotifier.initChannels(this)
        startForeground(SignalNotifier.NOTIF_MONITOR_ID, SignalNotifier.monitorNotification(this, "等待开市"))
        scope.launch {
            while (true) {
                runCatching { tick() }
                delay(60_000)
            }
        }
    }

    private suspend fun tick() {
        val now = LocalDateTime.now()
        val codes = prefs.getStringSet("codes_v2", emptySet()) ?: emptySet()
        if (codes.isEmpty()) {
            updateStatus("无自选股")
            return
        }
        if (!isMarketOpen(now, codes)) {
            updateStatus("休市 · ${now.toLocalTime().format(timeFmt)}")
            return
        }
        checkIntraday(codes)
    }

    private suspend fun checkIntraday(codes: Set<String>) {
        val strategyKey = prefs.getString("strategy", "atr_turtle") ?: "atr_turtle"
        val strategyName = Strategies.NAMES[strategyKey] ?: strategyKey
        val today = LocalDate.now()
        val quotes = runCatching { QuoteApi.fetchQuotes(codes.joinToString(",")) }.getOrNull() ?: run {
            updateStatus("行情获取失败 ${LocalTime.now().format(timeFmt)}")
            return
        }
        val byNumeric = codes.associateBy { it.removePrefix("sh").removePrefix("sz").removePrefix("hk") }

        var changed = 0
        for (q in quotes) {
            val code = byNumeric[q.code] ?: continue
            if (!q.isTrading) continue

            val bars = cachedBars(code, today) ?: continue
            if (bars.size < 60) continue

            val signal = Strategies.intradaySignal(strategyKey, bars, q)
            // 去抖：只记录 BUY/SELL，忽略 HOLD；仅方向翻转才通知
            if (signal == Strategies.Signal.HOLD) continue
            val last = prefs.getString("intraday_sig_$code", null)
            if (last != signal.name) {
                val name = prefs.getString("name_$code", null) ?: q.name.ifBlank { Codes.display(code) }
                val text = when (signal) {
                    Strategies.Signal.BUY -> "买入信号"
                    Strategies.Signal.SELL -> "卖出信号"
                    Strategies.Signal.HOLD -> ""
                }
                SignalNotifier.sendSignal(
                    this, name,
                    "$text · ${String.format("%.2f", q.price)} (%+.2f%%)".format(q.changePct),
                    strategyName, 1000 + code.hashCode(),
                )
                prefs.edit().putString("intraday_sig_$code", signal.name).apply()
                changed++
            }
        }
        updateStatus(
            if (changed > 0) "发出 $changed 个信号 ${LocalTime.now().format(timeFmt)}"
            else "监控中 · 无新信号 ${LocalTime.now().format(timeFmt)}"
        )
    }

    /** 当日历史日K只拉一次，盘中复用。 */
    private suspend fun cachedBars(code: String, today: LocalDate): List<com.quantpilot.app.data.Bar>? {
        barsCache[code]?.let { (date, bars) -> if (date == today) return bars }
        val bars = runCatching {
            QuoteApi.fetchDailyPaged(code, LocalDate.of(2022, 1, 1), today)
        }.getOrNull() ?: return null
        barsCache[code] = today to bars
        return bars
    }

    private fun updateStatus(text: String) {
        startForeground(SignalNotifier.NOTIF_MONITOR_ID, SignalNotifier.monitorNotification(this, text))
    }

    /** A股 9:30-15:00，港股 9:30-16:00（简化，不含午休判断）。 */
    private fun isMarketOpen(now: LocalDateTime, codes: Set<String>): Boolean {
        if (now.dayOfWeek.value > 5) return false
        val t = now.toLocalTime()
        val hasA = codes.any { !it.startsWith("hk") }
        val hasHk = codes.any { it.startsWith("hk") }
        val aOpen = hasA && t >= LocalTime.of(9, 30) && t < LocalTime.of(15, 0)
        val hkOpen = hasHk && t >= LocalTime.of(9, 30) && t < LocalTime.of(16, 0)
        return aOpen || hkOpen
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
