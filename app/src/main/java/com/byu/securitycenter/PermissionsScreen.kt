package com.byu.securitycenter

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byu.securitycenter.data.AppDb
import com.byu.securitycenter.data.LogEntry
import com.byu.securitycenter.security.AppPerm
import com.byu.securitycenter.security.PermCategory
import com.byu.securitycenter.security.PermissionAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var cache: List<AppPerm>? = null

private fun open(ctx: android.content.Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
internal fun PermissionsScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).logDao() }
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(cache) }
    var scanning by remember { mutableStateOf(false) }
    var filterKey by remember { mutableStateOf("REVIEW") }
    var showSystem by remember { mutableStateOf(false) }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { PermissionAnalyzer.scan(ctx) }
            cache = result
            apps = result
            scanning = false
            dao.insert(
                LogEntry(
                    type = "CHECK",
                    message = "Pemindaian izin: ${result.size} aplikasi, ${result.count { it.needsReview }} perlu ditinjau"
                )
            )
        }
    }
    LaunchedEffect(Unit) { if (cache == null) scan() }

    val all = apps.orEmpty()
    val list = all
        .filter { showSystem || !it.isSystem }
        .filter { a ->
            when (filterKey) {
                "ALL" -> true
                "REVIEW" -> a.needsReview
                else -> a.items.any { it.category.name == filterKey && it.granted == true }
            }
        }
    val chips = listOf("REVIEW" to "Perlu ditinjau", "ALL" to "Semua") +
        listOf(PermCategory.CAMERA, PermCategory.MICROPHONE, PermCategory.LOCATION,
            PermCategory.CONTACTS, PermCategory.SMS).map { it.name to it.label }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("APP PERMISSION ANALYZER", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (apps == null) "Memindai aplikasi…"
                        else "${all.size} aplikasi dipindai • ${all.count { it.needsReview }} perlu ditinjau",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Batasan: hanya aplikasi dengan ikon peluncur yang terlihat (aplikasi ini sengaja tidak " +
                            "meminta akses ke semua paket). \"Perlu ditinjau\" adalah penanda sederhana, bukan " +
                            "penilaian berbahaya; banyak aplikasi sah memakai izin ini. Status izin akses khusus " +
                            "tidak bisa dibaca di sini.",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scan() }, enabled = !scanning) {
                    Text(if (scanning) "Memindai…" else "Pindai ulang")
                }
                OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_PRIVACY_SETTINGS)) }) {
                    Text("Izin & privasi")
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips) { (key, label) ->
                    FilterChip(selected = filterKey == key, onClick = { filterKey = key }, label = { Text(label) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
                Spacer(Modifier.width(8.dp))
                Text("Tampilkan aplikasi sistem", fontSize = 13.sp)
            }
        }
        if (apps != null && list.isEmpty()) {
            item { Text("Tidak ada aplikasi pada filter ini.", color = Color.Gray, fontSize = 13.sp) }
        }
        items(list, key = { it.pkg }) { AppCard(it) }
    }
}

@Composable
private fun AppCard(app: AppPerm) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    Text(app.pkg, fontSize = 11.sp, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (app.needsReview) "Perlu ditinjau" else "Tanpa penanda",
                        color = if (app.needsReview) Amber else Green,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text("${app.grantedCount} izin aktif", fontSize = 11.sp, color = Color.Gray)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                if (app.reviewReasons.isNotEmpty()) {
                    app.reviewReasons.forEach { Text("⚠ $it", color = Amber, fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                }
                if (app.items.isEmpty()) {
                    Text("Tidak ada izin sensitif yang diminta.", fontSize = 12.sp, color = Color.Gray)
                }
                app.items.groupBy { it.category }.forEach { (cat, items) ->
                    Text(cat.label, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    items.forEach {
                        val status = when (it.granted) {
                            true -> "aktif"
                            false -> "belum diberikan"
                            null -> "diminta (status khusus)"
                        }
                        Text("• ${it.name} — $status", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                TextButton(onClick = {
                    open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                }) { Text("Buka pengaturan izin aplikasi") }
            }
        }
    }
}
