package com.byu.securitycenter

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byu.securitycenter.data.AppDb
import com.byu.securitycenter.data.LogEntry
import com.byu.securitycenter.security.Check
import com.byu.securitycenter.security.DeviceChecks
import com.byu.securitycenter.security.Status
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Cyan = Color(0xFF00E5FF)
private val Green = Color(0xFF00E676)
private val Amber = Color(0xFFFFB300)
private val Surface1 = Color(0xFF121820)

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Shield),
    PERMISSIONS("Izin", Icons.Filled.AdminPanelSettings),
    NETWORK("Jaringan", Icons.Filled.Wifi),
    CHECKER("Periksa", Icons.Filled.FactCheck),
    LOG("Log", Icons.Filled.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan, background = Color(0xFF0A0E14),
                    surface = Surface1, onBackground = Color(0xFFE6EDF3), onSurface = Color(0xFFE6EDF3)
                )
            ) { App() }
        }
    }
}

@Composable
private fun App() {
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it, onClick = { tab = it },
                        icon = { Icon(it.icon, it.label) },
                        label = { Text(it.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen()
                Tab.LOG -> LogScreen()
                else -> ComingSoon(tab.label)
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).logDao() }
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf(DeviceChecks.run(ctx)) }
    val recent by dao.observeRecent().collectAsStateWithLifecycle(emptyList())
    val score = DeviceChecks.score(checks)
    val scoreColor = if (score >= 80) Green else Amber

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("SECURITY DASHBOARD", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { score / 100f }, color = scoreColor,
                            trackColor = Color(0xFF1E2733), strokeWidth = 8.dp, modifier = Modifier.size(110.dp))
                        Text("$score%", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = scoreColor)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Skor pemeriksaan konfigurasi", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Skor ini hanya menghitung pengaturan yang dapat dibaca aplikasi. " +
                            "Skor tinggi bukan jaminan perangkat bebas malware.",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    checks = DeviceChecks.run(ctx)
                    val review = checks.filter { it.status == Status.REVIEW }
                    scope.launch {
                        dao.insert(LogEntry(type = "CHECK",
                            message = "Pemeriksaan perangkat: skor ${DeviceChecks.score(checks)}%, ${review.size} item perlu ditinjau"))
                        review.forEach { dao.insert(LogEntry(type = "WARNING", message = "Perlu ditinjau: ${it.title} — ${it.detail}")) }
                    }
                }) { Text("Jalankan pemeriksaan") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Pengaturan keamanan") }
            }
        }
        items(checks) { CheckCard(it) }
        item { Text("Aktivitas terbaru", fontWeight = FontWeight.Bold, color = Cyan) }
        if (recent.isEmpty()) item { Text("Belum ada aktivitas tercatat.", color = Color.Gray, fontSize = 13.sp) }
        items(recent) { LogRow(it) }
    }
}

@Composable
private fun CheckCard(c: Check) {
    val color = when (c.status) { Status.OK -> Green; Status.REVIEW -> Amber; Status.INFO -> Cyan }
    val icon = when (c.status) { Status.OK -> Icons.Filled.CheckCircle; Status.REVIEW -> Icons.Filled.Warning; Status.INFO -> Icons.Filled.Info }
    var open by remember { mutableStateOf(false) }
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontWeight = FontWeight.SemiBold)
                    Text(c.detail, fontSize = 13.sp, color = Color.Gray)
                }
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text("Batasan: ${c.limit}", fontSize = 12.sp, color = Color(0xFF9FB3C8))
            }
        }
    }
}

@Composable
private fun LogScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).logDao() }
    val scope = rememberCoroutineScope()
    val logs by dao.observeAll().collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ACTIVITY LOG", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { dao.clear() } }) { Text("Hapus semua") }
        }
        if (logs.isEmpty()) Text("Belum ada catatan.", color = Color.Gray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(logs) { LogRow(it) } }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale("id", "ID")) }
    val color = if (e.type == "WARNING") Amber else Cyan
    Row(
        Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        Column {
            Text("${e.type} • ${fmt.format(Date(e.time))}", fontSize = 11.sp, color = color)
            Text(e.message, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ComingSoon(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Modul $name dibangun di tahap berikutnya.", color = Color.Gray)
    }
}
