package com.example.questmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.conscrypt.Conscrypt
import org.json.JSONArray
import org.json.JSONObject
import java.security.Security

data class AppInfo(val pkg: String, val bytes: Long)

class MainActivity : ComponentActivity() {
    private lateinit var adb: AdbManager
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val games by lazy { JSONObject(assets.open("games.json").bufferedReader().readText()) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        adb = AdbManager(this)
        setContent { Root() }
    }

    private fun gameName(p: String): String = games.optJSONObject(p)?.optString("name") ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
    } catch (e: Exception) { p }
    private fun goals(p: String) = games.optJSONObject(p)?.optJSONArray("goals")
        ?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

    private suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pkgs = adb.run("pm list packages -3").lines()
            .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
            .filter { it != packageName }
        val d = adb.run("dumpsys diskstats")
        fun nums(label: String) = Regex("$label: \\[([^\\]]*)]").find(d)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().toLongOrNull() ?: 0L } ?: emptyList()
        val names = Regex("Package Names: \\[([^\\]]*)]").find(d)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().trim('"') } ?: emptyList()
        val a = nums("App Sizes"); val dt = nums("App Data Sizes"); val c = nums("Cache Sizes")
        pkgs.map { p ->
            val i = names.indexOf(p)
            val total = if (i >= 0) a.getOrElse(i) { 0 } + dt.getOrElse(i) { 0 } + c.getOrElse(i) { 0 } else 0L
            AppInfo(p, total)
        }
    }

    @Composable
    fun Root() {
        var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
        var bySize by remember { mutableStateOf(prefs.getBoolean("bySize", true)) }
        var gb by remember { mutableStateOf(prefs.getBoolean("gb", false)) }
        var confirm by remember { mutableStateOf(prefs.getBoolean("confirm", true)) }
        var tab by remember { mutableIntStateOf(0) }
        var status by remember { mutableStateOf(if (adb.isConnected) "Connected" else "Not connected") }
        var apps by remember { mutableStateOf(listOf<AppInfo>()) }
        var open by remember { mutableStateOf<AppInfo?>(null) }
        val scope = rememberCoroutineScope()
        fun save(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
        fun size(b: Long) = if (gb) "%.2f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)
        fun refresh() = scope.launch {
            status = "Loading apps…"
            status = try { apps = loadApps(); "Connected" } catch (e: Exception) { "Error: ${e.message}" }
        }

        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    TabRow(tab) {
                        listOf("Connect", "Apps", "For you", "Settings").forEachIndexed { i, t ->
                            Tab(tab == i, { tab = i }) { Text(t, Modifier.padding(16.dp)) }
                        }
                    }
                    when (tab) {
                        0 -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Status: $status")
                            Text("Settings → System → Developer → Wireless debugging → Pair device with pairing code. Enter the values shown there.")
                            var pp by remember { mutableStateOf("") }
                            var code by remember { mutableStateOf("") }
                            var cp by remember { mutableStateOf("") }
                            OutlinedTextField(pp, { pp = it }, label = { Text("Pairing port") })
                            OutlinedTextField(code, { code = it }, label = { Text("Pairing code") })
                            OutlinedTextField(cp, { cp = it }, label = { Text("Connect port (main Wireless debugging screen)") })
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button({ scope.launch {
                                    status = try {
                                        withContext(Dispatchers.IO) { adb.pair("127.0.0.1", pp.toInt(), code) }
                                        "Paired. Now press Connect."
                                    } catch (e: Exception) { "Pair failed: ${e.message}" }
                                } }) { Text("Pair") }
                                Button({ scope.launch {
                                    status = try {
                                        withContext(Dispatchers.IO) { adb.connect("127.0.0.1", cp.toInt()) }
                                        refresh(); tab = 1; "Connected"
                                    } catch (e: Exception) { "Connect failed: ${e.message}" }
                                } }) { Text("Connect") }
                            }
                        }
                        1 -> Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button({ refresh() }) { Text("Refresh") }
                                Spacer(Modifier.width(12.dp)); Text(status)
                            }
                            val list = if (bySize) apps.sortedByDescending { it.bytes } else apps.sortedBy { gameName(it.pkg).lowercase() }
                            LazyColumn {
                                items(list) { a ->
                                    ListItem(
                                        headlineContent = { Text(gameName(a.pkg)) },
                                        supportingContent = { Text(a.pkg) },
                                        trailingContent = { Text(size(a.bytes)) },
                                        modifier = Modifier.clickable { open = a })
                                    HorizontalDivider()
                                }
                            }
                        }
                        2 -> Recs(apps)
                        else -> Column(Modifier.padding(24.dp)) {
                            @Composable fun sw(label: String, v: Boolean, f: (Boolean) -> Unit) =
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, Modifier.weight(1f)); Switch(v, f)
                                }
                            sw("Dark theme", dark) { dark = it; save("dark", it) }
                            sw("Sort by size (off = A–Z)", bySize) { bySize = it; save("bySize", it) }
                            sw("Show sizes in GB", gb) { gb = it; save("gb", it) }
                            sw("Confirm before uninstalling", confirm) { confirm = it; save("confirm", it) }
                        }
                    }
                }
                open?.let { a -> Detail(a, ::size, confirm, { open = null }) { refresh() } }
            }
        }
    }

    private val allGenres = listOf("Rhythm", "Shooter", "Social", "Party", "Puzzle", "Adventure", "Horror",
        "Sports", "Fitness", "Sandbox", "Strategy", "Action", "Comedy")
    private fun genresOf(p: String): Set<String> {
        val seeded = games.optJSONObject(p)?.optJSONArray("genres")?.let { a -> List(a.length()) { a.getString(it) } }?.toSet() ?: emptySet()
        return seeded + (prefs.getStringSet("genres_$p", emptySet()) ?: emptySet())
    }
    private val catalog by lazy {
        val a = JSONArray(assets.open("catalog.json").bufferedReader().readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i); val g = o.getJSONArray("genres")
            val r = o.optJSONArray("related")
            Triple(o.getString("name"), List(g.length()) { g.getString(it) }, List(r?.length() ?: 0) { r!!.getString(it) })
        }
    }

    private fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }

    @Composable
    fun Recs(apps: List<AppInfo>) {
        var wish by remember { mutableStateOf(prefs.getStringSet("wishlist", emptySet()) ?: emptySet()) }
        var newName by remember { mutableStateOf("") }
        val names = apps.map { norm(gameName(it.pkg)) }
        val matched = catalog.filter { c -> val k = norm(c.first); names.any { it == k || (k.length > 4 && it.contains(k)) } }
        val weights = (matched.flatMap { it.second } + apps.flatMap { genresOf(it.pkg) }).groupingBy { it }.eachCount()
        val ownedKeys = matched.map { norm(it.first) }.toSet()
        fun related(c: Triple<String, List<String>, List<String>>) = matched.filter { m ->
            norm(c.first) in m.third.map { norm(it) } || norm(m.first) in c.third.map { norm(it) } }
        fun score(c: Triple<String, List<String>, List<String>>) =
            related(c).size * 3 + c.second.sumOf { weights[it] ?: 0 }
        fun saveWish(w: Set<String>) { wish = w; prefs.edit().putStringSet("wishlist", w).apply() }
        fun entry(e: String) = e.substringBefore("|")
        val wishKeys = wish.map { norm(entry(it)) }.toSet()
        fun store(n: String) = startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.meta.com/experiences/search/?q=" + Uri.encode(n))))
        val picks = catalog.filter { norm(it.first) !in ownedKeys && norm(it.first) !in wishKeys && score(it) > 0 }
            .sortedByDescending { score(it) }.take(8)
        LazyColumn(Modifier.padding(16.dp)) {
            item {
                Text("Related to your installed apps", style = MaterialTheme.typography.titleMedium)
                if (matched.isEmpty()) Text("Connect on the first tab so I can see what you have installed.")
            }
            items(picks) { c ->
                val r = related(c).map { it.first }
                ListItem(headlineContent = { Text(c.first) },
                    supportingContent = { Text(if (r.isNotEmpty()) "Related to " + r.joinToString() else "Similar genre: " + c.second.joinToString()) },
                    trailingContent = { Row {
                        TextButton({ store(c.first) }) { Text("Store") }
                        TextButton({ saveWish(wish + c.first) }) { Text("+ Wish") }
                    } })
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text("Your wishlist (most related first)", style = MaterialTheme.typography.titleMedium)
                Text("Meta doesn't share your store wishlist with apps, so keep it here.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("Game name") })
                    TextButton({ val n = newName.trim(); if (n.isNotEmpty()) { saveWish(wish + n); newName = "" } }) { Text("Add") }
                }
            }
            items(wish.map { w -> w to catalog.firstOrNull { norm(it.first) == norm(entry(w)) } }
                .sortedByDescending { (_, c) -> c?.let { score(it) } ?: 0 }) { (raw, c) ->
                ListItem(headlineContent = { Text(entry(raw)) },
                    supportingContent = { Text(when {
                        c == null -> "Not in my catalog, so no match info"
                        related(c).isNotEmpty() -> "Related to " + related(c).joinToString { it.first }
                        else -> "Genres: " + c.second.joinToString()
                    }) },
                    trailingContent = { Row {
                        TextButton({ store(entry(raw)) }) { Text("Store") }
                        TextButton({ saveWish(wish - raw) }) { Text("✕") }
                    } })
            }
        }
    }

    @Composable
    fun Detail(a: AppInfo, size: (Long) -> String, confirm: Boolean, close: () -> Unit, done: () -> Unit) {
        val scope = rememberCoroutineScope()
        var ask by remember { mutableStateOf(false) }
        val key = "goals_${a.pkg}"; val ckey = "custom_${a.pkg}"
        var checked by remember { mutableStateOf(prefs.getStringSet(key, emptySet()) ?: emptySet()) }
        var custom by remember { mutableStateOf(prefs.getStringSet(ckey, emptySet()) ?: emptySet()) }
        var newGoal by remember { mutableStateOf("") }
        var gset by remember { mutableStateOf(genresOf(a.pkg)) }
        var info by remember { mutableStateOf("Loading details…") }
        LaunchedEffect(a.pkg) {
            info = try {
                withContext(Dispatchers.IO) {
                    val d = adb.run("dumpsys package ${a.pkg}")
                    fun f(k: String) = Regex("$k=([^\\n]+)").find(d)?.groupValues?.get(1)?.trim() ?: "unknown"
                    "Version: ${f("versionName")}\nInstalled: ${f("firstInstallTime")}\nLast updated: ${f("lastUpdateTime")}"
                }
            } catch (e: Exception) { "Details unavailable" }
        }
        fun uninstall() = scope.launch {
            withContext(Dispatchers.IO) { adb.run("pm uninstall ${a.pkg}") }; close(); done()
        }
        AlertDialog(onDismissRequest = close,
            title = { Text(gameName(a.pkg)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Storage used: ${size(a.bytes)}\n${a.pkg}\n$info")
                    Text("\nGenres (used for recommendations)")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        allGenres.forEach { g ->
                            TextButton({
                                gset = if (g in gset) gset - g else gset + g
                                prefs.edit().putStringSet("genres_${a.pkg}", gset).apply()
                            }) { Text(if (g in gset) "✓ $g" else g) }
                        }
                    }
                    Text("\nGoals & tips (tick what you've done)")
                    val g = goals(a.pkg)
                    if (g.isEmpty() && custom.isEmpty()) Text("None yet. Add your own below.")
                    (g + custom.sorted()).forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(t in checked, { on ->
                                checked = if (on) checked + t else checked - t
                                prefs.edit().putStringSet(key, checked).apply()
                            })
                            Text(t, Modifier.weight(1f))
                            if (t in custom) TextButton({
                                custom = custom - t; checked = checked - t
                                prefs.edit().putStringSet(ckey, custom).putStringSet(key, checked).apply()
                            }) { Text("✕") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newGoal, { newGoal = it }, Modifier.weight(1f), label = { Text("Add a goal or tip") })
                        TextButton({
                            if (newGoal.isNotBlank()) {
                                custom = custom + newGoal.trim(); newGoal = ""
                                prefs.edit().putStringSet(ckey, custom).apply()
                            }
                        }) { Text("Add") }
                    }
                    TextButton({ startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.meta.com/experiences/search/?q=" + Uri.encode(gameName(a.pkg))))) }) {
                        Text("Open Meta Store page")
                    }
                }
            },
            confirmButton = { TextButton({ if (confirm) ask = true else uninstall() }) { Text("Uninstall") } },
            dismissButton = { TextButton(close) { Text("Close") } })
        if (ask) AlertDialog(onDismissRequest = { ask = false },
            title = { Text("Uninstall ${gameName(a.pkg)}?") },
            confirmButton = { TextButton({ ask = false; uninstall() }) { Text("Uninstall") } },
            dismissButton = { TextButton({ ask = false }) { Text("Cancel") } })
    }
}
