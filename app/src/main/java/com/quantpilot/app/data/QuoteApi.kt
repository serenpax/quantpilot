package com.quantpilot.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 腾讯行情接口（与 akshare 底层同源）。
 * 实时: https://qt.gtimg.cn/q=sh600519      （GBK 文本）
 * 历史: https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=...
 */
object QuoteApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 拉取前复权日K，count 上限约 640 根/次；不足时返回全部。 */
    suspend fun fetchDaily(code: String, start: LocalDate, end: LocalDate): List<Bar> =
        withContext(Dispatchers.IO) {
            val param = "$code,day,$start,$end,640,qfq"
            val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$param"
            val body = get(url)
            val root = JSONObject(body).getJSONObject("data").getJSONObject(code)
            val arr: JSONArray = when {
                root.has("qfqday") -> root.getJSONArray("qfqday")
                root.has("day") -> root.getJSONArray("day")
                else -> throw IOException("K线数据为空: $code")
            }
            buildList {
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONArray(i)
                    // 顺序: date, open, close, high, low, volume
                    add(
                        Bar(
                            date = LocalDate.parse(k.getString(0)),
                            open = k.optString(1).toFloatOrNull() ?: continue,
                            close = k.optString(2).toFloatOrNull() ?: continue,
                            high = k.optString(3).toFloatOrNull() ?: continue,
                            low = k.optString(4).toFloatOrNull() ?: continue,
                            volume = k.optString(5).toFloatOrNull() ?: 0f,
                        )
                    )
                }
            }
        }

    /** 分页拉取长区间：接口单次最多约640根，从终点向前逐段拼接。 */
    suspend fun fetchDailyPaged(code: String, start: LocalDate, end: LocalDate): List<Bar> {
        val all = LinkedHashMap<LocalDate, Bar>()
        var cursorEnd = end
        while (true) {
            val batch = runCatching { fetchDaily(code, start, cursorEnd) }.getOrNull() ?: break
            if (batch.isEmpty()) break
            batch.forEach { all[it.date] = it }
            if (batch.size < 640) break          // 已覆盖到 start 之前
            val earliest = batch.first().date
            if (earliest <= start) break
            cursorEnd = earliest.minusDays(1)    // 继续往前翻
        }
        return all.values.filter { it.date >= start }.sortedBy { it.date }
    }

    /** 实时报价，支持逗号分隔多只（如 "sh600519,hk01810"）。 */
    suspend fun fetchQuotes(codes: String): List<Quote> = withContext(Dispatchers.IO) {
        val url = "https://qt.gtimg.cn/q=$codes"
        val text = get(url, charset = "GBK")
        text.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq < 0 || !line.contains('\"')) return@mapNotNull null
                val fields = line.substring(eq + 2, line.lastIndexOf('~')).split("~")
                if (fields.size < 35) return@mapNotNull null
                runCatching {
                    val price = fields[3].toFloat()
                    val prevClose = fields[4].toFloat()
                    Quote(
                        code = fields[2],
                        name = fields[1],
                        price = price,
                        prevClose = prevClose,
                        open = fields[5].toFloatOrNull() ?: 0f,
                        high = fields[33].toFloatOrNull() ?: 0f,
                        low = fields[34].toFloatOrNull() ?: 0f,
                        changePct = if (prevClose > 0) (price - prevClose) / prevClose * 100 else 0f,
                    )
                }.getOrNull()
            }
            .toList()
    }

    private fun get(url: String, charset: String = "UTF-8"): String {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}: $url")
            val body = it.body ?: throw IOException("空响应: $url")
            return String(body.bytes(), charset(charset))
        }
    }
}
