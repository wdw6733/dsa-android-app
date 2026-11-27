package com.ww.dsa

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

fun prefs(ctx: Context) = ctx.getSharedPreferences("dsa", Context.MODE_PRIVATE)
fun baseUrl(ctx: Context) = prefs(ctx).getString("server", "http://100.103.58.5:30800")!!.trimEnd('/')

fun httpJson(url: String, method: String = "GET", body: String? = null): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 15000
    c.readTimeout = 60000
    if (body != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
    }
    val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
    return JSONObject(text)
}

fun httpText(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000; c.readTimeout = 60000
    return c.inputStream.bufferedReader().readText()
}

data class Cand(val rank: Int, val code: String, val name: String, val score: Double, val reason: String, val risk: String, val thesis: String)
data class Strat(val id: String, val name: String)
data class Report(val id: String, val title: String, val time: String)

@Composable
fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("DSA 选股") }) },
        bottomBar = {
            NavigationBar {
                listOf("选股", "报告", "分析", "设置").forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(t) }, icon = {})
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> PicksTab(ctx)
                1 -> ReportsTab(ctx)
                2 -> AnalyzeTab(ctx)
                else -> SettingsTab(ctx)
            }
        }
    }
}

@Composable
fun PicksTab(ctx: Context) {
    var cands by remember { mutableStateOf<List<Cand>>(emptyList()) }
    var strats by remember { mutableStateOf<List<Strat>>(emptyList()) }
    var strat by remember { mutableStateOf("balanced_alpha") }
    var status by remember { mutableStateOf("加载中…") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    fun loadLatest() {
        thread {
            try {
                val b = baseUrl(ctx)
                val s = httpJson("$b/api/v1/screening/strategies").optJSONArray("strategies") ?: JSONArray()
                val sl = (0 until s.length()).map { val o = s.getJSONObject(it); Strat(o.optString("id"), o.optString("name")) }
                val h = httpJson("$b/api/v1/screening/history?limit=1")
                val runs = h.optJSONArray("runs")
                if (runs == null || runs.length() == 0) { status = "暂无选股记录"; return@thread }
                val rid = runs.getJSONObject(0).optString("run_id")
                val d = httpJson("$b/api/v1/screening/history/$rid")
                val res = d.optJSONObject("result") ?: d
                val arr = res.optJSONArray("candidates") ?: JSONArray()
                val list = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Cand(o.optInt("rank"), o.optString("code"), o.optString("name"), o.optDouble("score"),
                        o.optString("reason"), o.optString("risk_summary"), o.optString("llm_thesis"))
                }
                strats = sl; cands = list
                status = "策略 ${res.optString("strategy")} · ${if (res.optBoolean("llm_ranked")) "LLM 重排" else "因子排序"} · ${res.optString("llm_model_used")}"
            } catch (e: Exception) { status = "加载失败：${e.message}" }
        }
    }
    LaunchedEffect(Unit) { loadLatest() }

    fun runScreening() {
        status = "选股任务启动中…"
        thread {
            try {
                val b = baseUrl(ctx)
                val body = JSONObject().put("market", "cn").put("strategy", strat).put("max_results", 10).toString()
                val t = httpJson("$b/api/v1/screening/screen/tasks", "POST", body)
                val tid = t.optString("task_id", t.optString("id"))
                var done = false
                while (!done) {
                    Thread.sleep(5000)
                    val st = httpJson("$b/api/v1/screening/screen/tasks/$tid")
                    val s = st.optString("status")
                    status = "任务 $s ${st.optString("progress", "")}"
                    if (s in listOf("completed", "failed", "success", "error")) done = true
                }
                loadLatest()
            } catch (e: Exception) { status = "触发失败：${e.message}" }
        }
    }

    Column(Modifier.padding(12.dp)) {
        Row {
            Box {
                Button(onClick = { menuOpen = true }) { Text(strats.firstOrNull { it.id == strat }?.name ?: strat) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    strats.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { strat = s.id; menuOpen = false }) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { runScreening() }) { Text("跑选股") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { loadLatest() }) { Text("刷新") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            items(cands) { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = if (expanded == c.code) null else c.code }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${c.rank}. ${c.name} ${c.code}  ${"%.1f".format(c.score)}", fontWeight = FontWeight.Bold)
                        Text(c.reason)
                        if (expanded == c.code) {
                            if (c.thesis.isNotEmpty()) Text("逻辑：${c.thesis}")
                            if (c.risk.isNotEmpty()) Text("风险：${c.risk}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportsTab(ctx: Context) {
    var reports by remember { mutableStateOf<List<Report>>(emptyList()) }
    var detail by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("加载中…") }
    LaunchedEffect(Unit) {
        thread {
            try {
                val b = baseUrl(ctx)
                val h = httpJson("$b/api/v1/history?page=1&page_size=30")
                val arr = h.optJSONArray("items") ?: JSONArray()
                reports = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Report(o.optString("id", o.optString("record_id")), o.optString("stock_name", o.optString("stock_code")), o.optString("created_at"))
                }
                status = if (reports.isEmpty()) "暂无报告（每日分析后生成）" else "共 ${reports.size} 份"
            } catch (e: Exception) { status = "加载失败：${e.message}" }
        }
    }
    Column(Modifier.padding(12.dp)) {
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (detail.isNotEmpty()) {
            OutlinedButton(onClick = { detail = "" }) { Text("返回列表") }
            LazyColumn { item { Text(detail) } }
        } else {
            LazyColumn {
                items(reports) { r ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        thread {
                            try { detail = httpText("${baseUrl(ctx)}/api/v1/history/${r.id}/markdown") }
                            catch (e: Exception) { detail = "加载失败：${e.message}" }
                        }
                    }) { Column(Modifier.padding(12.dp)) { Text(r.title, fontWeight = FontWeight.Bold); Text(r.time) } }
                }
            }
        }
    }
}

@Composable
fun AnalyzeTab(ctx: Context) {
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Column(Modifier.padding(12.dp)) {
        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("股票代码，如 600519") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            status = "提交中…"
            thread {
                try {
                    val b = baseUrl(ctx)
                    val body = JSONObject().put("stock_code", code.trim()).put("async_mode", true).toString()
                    val t = httpJson("$b/api/v1/analysis/analyze", "POST", body)
                    val tid = t.optString("task_id")
                    if (tid.isEmpty()) { status = "已提交：${t.toString().take(200)}"; return@thread }
                    var done = false
                    while (!done) {
                        Thread.sleep(5000)
                        val st = httpJson("$b/api/v1/analysis/status/$tid")
                        val s = st.optString("status")
                        status = "任务 $s ${st.optString("message", "")}"
                        if (s in listOf("completed", "failed", "success", "error")) done = true
                    }
                    status += "，去报告页查看"
                } catch (e: Exception) { status = "失败：${e.message}" }
            }
        }) { Text("开始分析") }
        Text(status, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun SettingsTab(ctx: Context) {
    var server by remember { mutableStateOf(baseUrl(ctx)) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.padding(12.dp)) {
        OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("服务器地址") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            prefs(ctx).edit().putString("server", server.trim()).apply()
            thread {
                msg = try {
                    val h = httpJson("${server.trim().trimEnd('/')}/api/health")
                    "连接正常：${h.optString("status")}"
                } catch (e: Exception) { "连接失败：${e.message}" }
            }
        }) { Text("保存并测试") }
        Text(msg, modifier = Modifier.padding(top = 12.dp))
        Text("先连 Tailscale 地址；切公网域名前须先给 DSA 开鉴权。", style = MaterialTheme.typography.bodySmall)
    }
}
