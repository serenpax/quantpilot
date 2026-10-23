package com.quantpilot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quantpilot.app.data.Backtester
import com.quantpilot.app.data.Codes
import com.quantpilot.app.data.Quote
import com.quantpilot.app.data.Strategies
import com.quantpilot.app.ui.StockChart

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2563EB))) {
                Surface(Modifier.fillMaxSize()) { QuantPilotApp() }
            }
        }
    }
}

@Composable
fun QuantPilotApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    // Android 13+ 需要运行时申请通知权限
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 自适应布局：展开横屏 → 左侧股票栏 + 右侧详情；折叠小屏/展开竖屏 → 顶部标签栏 + 全宽详情
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wideLandscape = maxWidth > maxHeight && maxWidth >= 700.dp
        val isShort = maxHeight < 620.dp
        if (wideLandscape) {
            Row(Modifier.fillMaxSize()) {
                StockSideRail(
                    vm = vm,
                    onAdd = { showAdd = true },
                    modifier = Modifier.width(200.dp).fillMaxHeight(),
                )
                VerticalDivider()
                DetailPanel(
                    vm,
                    Modifier.weight(1f).fillMaxHeight(),
                    compact = isShort,
                    wide = true,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                StockTabBar(
                    vm = vm,
                    onAdd = { showAdd = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                DetailPanel(vm, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("好") } },
            title = { Text("提示") },
            text = { Text(msg) },
        )
    }
    if (showAdd) {
        AddCodeDialog(
            onDismiss = { showAdd = false },
            onAdd = { vm.addCode(it); showAdd = false },
        )
    }
}

/* ---------------- 顶部自选标签栏 ---------------- */

@Composable
private fun StockTabBar(
    vm: MainViewModel,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = vm.state.collectAsState().value
    Column(modifier.background(Color(0xFFF6F7F9))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (s.marketOpen) "● 开市监控中" else "○ 休市",
                fontSize = 12.sp,
                color = if (s.marketOpen) Color(0xFF27AE60) else Color(0xFF999999),
            )
            if (s.lastQuoteTime.isNotEmpty()) {
                Text(" · ${s.lastQuoteTime}", fontSize = 12.sp, color = Color(0xFFAAAAAA))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, "添加", tint = Color(0xFF444444))
            }
            IconButton(onClick = { vm.refreshNow() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, "刷新", tint = Color(0xFF444444))
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(s.codes) { code ->
                StockTab(
                    code = code,
                    quote = s.quotes[code],
                    selected = code == s.selectedCode,
                    onClick = { vm.select(code) },
                    onDelete = { vm.removeCode(code) },
                )
            }
        }
    }
}

@Composable
private fun StockTab(
    code: String,
    quote: Quote?,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val up = (quote?.changePct ?: 0f) >= 0f
    val priceColor = if (up) Color(0xFFE74C3C) else Color(0xFF27AE60)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color(0xFF2563EB) else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    quote?.name ?: Codes.display(code),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else Color(0xFF333333),
                    maxLines = 1,
                )
                if (quote != null && quote.isTrading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            String.format("%.2f", quote.price),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else priceColor,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            String.format("%+.2f%%", quote.changePct),
                            fontSize = 11.sp,
                            color = if (selected) Color(0xFFDCE7FF) else priceColor,
                        )
                    }
                } else {
                    Text(
                        Codes.display(code), fontSize = 11.sp,
                        color = if (selected) Color(0xFFDCE7FF) else Color(0xFFAAAAAA),
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Delete, "删除",
                    tint = Color(0xFFDCE7FF),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onDelete),
                )
            }
        }
    }
}

/* ---------------- 横屏侧边股票栏 ---------------- */

@Composable
private fun StockSideRail(
    vm: MainViewModel,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = vm.state.collectAsState().value
    Column(modifier.background(Color(0xFFF6F7F9))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (s.marketOpen) "● 开市" else "○ 休市",
                fontSize = 12.sp,
                color = if (s.marketOpen) Color(0xFF27AE60) else Color(0xFF999999),
            )
            if (s.lastQuoteTime.isNotEmpty()) {
                Text(" · ${s.lastQuoteTime}", fontSize = 11.sp, color = Color(0xFFAAAAAA))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, "添加", tint = Color(0xFF444444))
            }
            IconButton(onClick = { vm.refreshNow() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, "刷新", tint = Color(0xFF444444))
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(s.codes) { code ->
                RailRow(
                    code = code,
                    quote = s.quotes[code],
                    selected = code == s.selectedCode,
                    onClick = { vm.select(code) },
                    onDelete = { vm.removeCode(code) },
                )
            }
        }
    }
}

@Composable
private fun RailRow(
    code: String,
    quote: Quote?,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val up = (quote?.changePct ?: 0f) >= 0f
    val priceColor = if (up) Color(0xFFE74C3C) else Color(0xFF27AE60)
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF2563EB) else Color.White,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    quote?.name ?: Codes.display(code),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else Color(0xFF333333),
                    maxLines = 1,
                )
                Text(
                    Codes.display(code), fontSize = 11.sp,
                    color = if (selected) Color(0xFFDCE7FF) else Color(0xFF999999),
                )
            }
            if (quote != null && quote.isTrading) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        String.format("%.2f", quote.price),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White else priceColor,
                    )
                    Text(
                        String.format("%+.2f%%", quote.changePct),
                        fontSize = 11.sp,
                        color = if (selected) Color(0xFFDCE7FF) else priceColor,
                    )
                }
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Delete, "删除",
                        tint = Color(0xFFDCE7FF),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(onClick = onDelete),
                    )
                }
            } else if (selected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.Delete, "删除",
                    tint = Color(0xFFDCE7FF),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onDelete),
                )
            }
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(Color(0xFFE0E0E0)))
}

/* ---------------- 详情面板 ---------------- */

@Composable
private fun DetailPanel(vm: MainViewModel, modifier: Modifier = Modifier, compact: Boolean = false, wide: Boolean = false) {
    val s = vm.state.collectAsState().value
    val code = s.selectedCode
    if (code == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("添加并选择一只股票开始", color = Color(0xFF999999)) }
        return
    }
    val quote = s.quotes[code]
    var expanded by remember { mutableStateOf(false) }

    // 联动视口状态：两张图共用，缩放/平移任意一张另一张同步
    val n = s.backtest?.dates?.size ?: 0
    var sharedVisibleCount by remember(code) { mutableFloatStateOf(120f) }
    var sharedStartIndex by remember(code, n) {
        mutableFloatStateOf((n - 120).coerceAtLeast(0).toFloat())
    }

    Column(modifier.padding(horizontal = 10.dp, vertical = 6.dp).verticalScroll(rememberScrollState())) {

        // ── 第一行：股票名 + 代码 | 实时价涨跌 | 策略选择 + 信号徽章 ──
        // 开市+有实时价时用盘中信号，否则用收盘信号
        val sig = if (s.bars.size > 1) {
            if (s.marketOpen && quote != null && quote.isTrading) {
                Strategies.intradaySignal(s.strategyKey, s.bars, quote)
            } else {
                Strategies.signal(s.strategyKey, s.bars)
            }
        } else Strategies.Signal.HOLD
        val (sigTxt, sigColor) = when (sig) {
            Strategies.Signal.BUY  -> "买入" to Color(0xFFE74C3C)
            Strategies.Signal.SELL -> "卖出" to Color(0xFF27AE60)
            Strategies.Signal.HOLD -> "持仓" to Color(0xFF888888)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 股票名 + 代码
            Column {
                Text(quote?.name ?: Codes.display(code), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(Codes.display(code), fontSize = 10.sp, color = Color(0xFF888888))
            }
            // 实时价
            if (quote != null && quote.isTrading) {
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        String.format("%.2f", quote.price),
                        fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = if (quote.changePct >= 0) Color(0xFFE74C3C) else Color(0xFF27AE60),
                    )
                    Text(
                        String.format("%+.2f%%", quote.changePct),
                        fontSize = 11.sp,
                        color = if (quote.changePct >= 0) Color(0xFFE74C3C) else Color(0xFF27AE60),
                    )
                }
            }
            // 横屏时把回测统计嵌入此行，省掉单独一行
            if (wide && s.backtest != null) {
                Spacer(Modifier.width(12.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val st = s.backtest!!.stats
                    StatCell2("收益", String.format("%+.1f%%", st.totalReturn),
                        if (st.totalReturn >= 0) Color(0xFFE74C3C) else Color(0xFF27AE60))
                    StatCell2("基准", String.format("%+.1f%%", st.benchmarkReturn), Color(0xFF666666))
                    StatCell2("回撤", String.format("%.1f%%", st.maxDrawdown), Color(0xFF27AE60))
                    StatCell2("夏普", String.format("%.2f", st.sharpe), Color(0xFF555555))
                    StatCell2("胜率", st.winRate?.let { String.format("%.0f%%", it) } ?: "-", Color(0xFF555555))
                    StatCell2("笔数", "${st.nTrades}", Color(0xFF555555))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            // 信号徽章
            Text(
                sigTxt,
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier
                    .background(sigColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
            // 策略下拉
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(Strategies.NAMES[s.strategyKey] ?: s.strategyKey, fontSize = 12.sp)
                    Text(" ▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    Strategies.NAMES.forEach { (key, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            vm.setStrategy(key); expanded = false
                        })
                    }
                }
            }
            if (s.busy) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        // ── 第二行：回测统计 ──
        Spacer(Modifier.height(5.dp))
        s.backtest?.let { bt ->
            if (!wide) {
                BacktestStatsCard(bt)
                Spacer(Modifier.height(6.dp))
            }
            Text("价格与买卖点（红买绿卖）", fontSize = 11.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(2.dp))
            StockChart(
                result = bt,
                mode = "PRICE",
                modifier = Modifier.fillMaxWidth().height(if (wide) 210.dp else if (compact) 190.dp else 260.dp),
                visibleCount = sharedVisibleCount,
                startIndex = sharedStartIndex,
                onViewportChange = { vc, si -> sharedVisibleCount = vc; sharedStartIndex = si },
            )
            Spacer(Modifier.height(6.dp))
            Text("策略净值（蓝） vs 买入持有（灰）", fontSize = 11.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(2.dp))
            StockChart(
                result = bt,
                mode = "EQUITY",
                modifier = Modifier.fillMaxWidth().height(if (wide) 190.dp else if (compact) 160.dp else 230.dp),
                visibleCount = sharedVisibleCount,
                startIndex = sharedStartIndex,
                onViewportChange = { vc, si -> sharedVisibleCount = vc; sharedStartIndex = si },
            )
        } ?: run {
            Text(
                if (s.busy) "正在计算…" else "K线不足，无法回测",
                color = Color(0xFF999999), fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun BacktestStatsCard(bt: Backtester.Result) {
    val st = bt.stats
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4FF))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCell2("总收益", String.format("%+.1f%%", st.totalReturn),
                if (st.totalReturn >= 0) Color(0xFFE74C3C) else Color(0xFF27AE60))
            StatCell2("基准", String.format("%+.1f%%", st.benchmarkReturn), Color(0xFF666666))
            StatCell2("回撤", String.format("%.1f%%", st.maxDrawdown), Color(0xFF27AE60))
            StatCell2("夏普", String.format("%.2f", st.sharpe), Color(0xFF555555))
            StatCell2("胜率", st.winRate?.let { String.format("%.0f%%", it) } ?: "-", Color(0xFF555555))
            StatCell2("笔数", "${st.nTrades}", Color(0xFF555555))
        }
    }
}

@Composable
private fun StatCell2(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Text(label, fontSize = 10.sp, color = Color(0xFF999999), maxLines = 1)
    }
}

/* ---------------- 小部件 ---------------- */

@Composable
private fun HorizontalDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE0E0E0)))
}

@Composable
private fun AddCodeDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加股票") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("A股如 600519，港股如 01810") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onAdd(text.trim()) }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
