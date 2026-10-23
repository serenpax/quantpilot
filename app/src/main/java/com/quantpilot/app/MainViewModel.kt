package com.quantpilot.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quantpilot.app.data.Backtester
import com.quantpilot.app.data.Bar
import com.quantpilot.app.data.Codes
import com.quantpilot.app.data.Quote
import com.quantpilot.app.data.QuoteApi
import com.quantpilot.app.data.Strategies
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class AppState(
    val codes: List<String> = emptyList(),          // 腾讯格式 sh600519
    val quotes: Map<String, Quote> = emptyMap(),
    val selectedCode: String? = null,
    val bars: List<Bar> = emptyList(),
    val strategyKey: String = "atr_turtle",
    val backtest: Backtester.Result? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val marketOpen: Boolean = false,
    val lastQuoteTime: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val prefs = app.getSharedPreferences("watchlist", Context.MODE_PRIVATE)
    private var pollJob: Job? = null

    init {
        // v2 键：升级后首次启动用新默认四只港股，旧 watchlist 不再读取
        val saved = prefs.getStringSet("codes_v2", null)
            ?.toList() ?: listOf("hk01810", "hk03896", "hk03888", "hk02208")
        _state.value = _state.value.copy(codes = saved)
        // 持久化默认策略，供 MonitorService 读取
        if (prefs.getString("strategy", null) == null) {
            prefs.edit().putString("strategy", _state.value.strategyKey).apply()
        }
        select(saved.first())
        startPolling()
        // 启动前台监控服务
        app.startForegroundService(Intent(app, MonitorService::class.java))
    }

    // ---------- 自选股 ----------

    fun addCode(input: String) {
        viewModelScope.launch {
            runCatching { Codes.normalize(input) }.fold(
                onSuccess = { code ->
                    if (code in _state.value.codes) return@launch
                    val codes = _state.value.codes + code
                    persist(codes)
                    _state.value = _state.value.copy(codes = codes)
                    refreshQuotes(force = true)
                    select(code)
                },
                onFailure = { showError(it.message ?: "代码错误") },
            )
        }
    }

    fun removeCode(code: String) {
        val codes = _state.value.codes - code
        persist(codes)
        _state.value = _state.value.copy(codes = codes)
        if (_state.value.selectedCode == code) codes.firstOrNull()?.let { select(it) }
    }

    fun select(code: String) {
        _state.value = _state.value.copy(selectedCode = code, backtest = null, error = null)
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching {
                // 回测起点 2022-01-01；接口单次最多640根，用分页拼接完整区间
                QuoteApi.fetchDailyPaged(code, LocalDate.of(2022, 1, 1), LocalDate.now())
            }.fold(
                onSuccess = { bars ->
                    _state.value = _state.value.copy(
                        bars = bars, busy = false,
                        backtest = _state.value.backtest, // 保留旧结果直到重跑
                    )
                    runBacktest()
                },
                onFailure = {
                    _state.value = _state.value.copy(busy = false)
                    showError("拉取K线失败: ${it.message}")
                },
            )
        }
    }

    fun setStrategy(key: String) {
        _state.value = _state.value.copy(strategyKey = key)
        prefs.edit().putString("strategy", key).apply()
        runBacktest()
    }

    fun runBacktest() {
        val s = _state.value
        if (s.bars.size < 60) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { Backtester.run(s.bars, s.strategyKey) }.fold(
                onSuccess = { _state.value = _state.value.copy(backtest = it, busy = false) },
                onFailure = { _state.value = _state.value.copy(busy = false); showError(it.message) },
            )
        }
    }

    // ---------- 实时轮询 ----------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val open = isMarketOpen()
                _state.value = _state.value.copy(marketOpen = open)
                if (open || _state.value.lastQuoteTime.isEmpty()) refreshQuotes()
                // 开市 15s 一轮，休市 5 分钟一轮（保留最后价格展示）
                delay(if (open) 15_000 else 300_000)
            }
        }
    }

    private fun refreshQuotes(force: Boolean = false) {
        val codes = _state.value.codes
        if (codes.isEmpty()) return
        viewModelScope.launch {
            if (force) _state.value = _state.value.copy(busy = true)
            runCatching { QuoteApi.fetchQuotes(codes.joinToString(",")) }.fold(
                onSuccess = { list ->
                    val byNumeric = codes.associateBy { it.removePrefix("sh").removePrefix("sz").removePrefix("hk") }
                    _state.value = _state.value.copy(
                        quotes = list.mapNotNull { q ->
                            byNumeric[q.code]?.let { full -> full to q }
                        }.toMap(),
                        busy = false,
                        lastQuoteTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                        error = null,
                    )
                    // 存储股票名称，供 MonitorService 通知使用
                    val ed = prefs.edit()
                    list.forEach { q ->
                        byNumeric[q.code]?.let { full -> ed.putString("name_$full", q.name) }
                    }
                    ed.apply()
                },
                onFailure = {
                    _state.value = _state.value.copy(busy = false)
                    if (force) showError("行情获取失败: ${it.message}")
                },
            )
        }
    }

    fun refreshNow() = refreshQuotes(force = true)

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun showError(msg: String?) {
        _state.value = _state.value.copy(error = msg)
    }

    private fun persist(codes: List<String>) {
        prefs.edit().putStringSet("codes_v2", codes.toSet()).apply()
    }

    /** A股 9:30-15:00，港股 9:30-16:00（简化，不含午休细节判断）。 */
    private fun isMarketOpen(): Boolean {
        val now = LocalDateTime.now()
        if (now.dayOfWeek.value > 5) return false
        val t = now.toLocalTime()
        val hasA = _state.value.codes.any { !it.startsWith("hk") }
        val hasHk = _state.value.codes.any { it.startsWith("hk") }
        val aOpen = hasA && (t >= LocalTime.of(9, 30) && t < LocalTime.of(15, 0))
        val hkOpen = hasHk && (t >= LocalTime.of(9, 30) && t < LocalTime.of(16, 0))
        return aOpen || hkOpen
    }
}
