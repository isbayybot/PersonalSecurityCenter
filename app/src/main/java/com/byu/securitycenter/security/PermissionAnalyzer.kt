package com.byu.securitycenter.security

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

enum class PermCategory(val label: String) {
    CAMERA("Kamera"),
    MICROPHONE("Mikrofon"),
    LOCATION("Lokasi"),
    CONTACTS("Kontak"),
    SMS("SMS"),
    PHONE("Telepon & log panggilan"),
    STORAGE("Penyimpanan & media"),
    CALENDAR("Kalender"),
    BODY("Sensor tubuh & aktivitas"),
    NEARBY("Perangkat sekitar"),
    SPECIAL("Akses khusus")
}

/** granted: true = diberikan, false = diminta tapi belum diberikan, null = izin khusus (status tidak bisa dibaca di sini) */
data class PermItem(val name: String, val category: PermCategory, val granted: Boolean?)

data class AppPerm(
    val label: String,
    val pkg: String,
    val isSystem: Boolean,
    val items: List<PermItem>,
    val reviewReasons: List<String>
) {
    val needsReview: Boolean get() = reviewReasons.isNotEmpty()
    val grantedCount: Int get() = items.count { it.granted == true }
}

object PermissionAnalyzer {

    private const val P = "android.permission."

    private val map: Map<String, PermCategory> = buildMap {
        put(P + "CAMERA", PermCategory.CAMERA)
        put(P + "RECORD_AUDIO", PermCategory.MICROPHONE)
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION")
            .forEach { put(P + it, PermCategory.LOCATION) }
        listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS")
            .forEach { put(P + it, PermCategory.CONTACTS) }
        listOf("READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH")
            .forEach { put(P + it, PermCategory.SMS) }
        listOf("READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "READ_CALL_LOG",
            "WRITE_CALL_LOG", "ANSWER_PHONE_CALLS", "PROCESS_OUTGOING_CALLS")
            .forEach { put(P + it, PermCategory.PHONE) }
        listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED",
            "ACCESS_MEDIA_LOCATION")
            .forEach { put(P + it, PermCategory.STORAGE) }
        listOf("READ_CALENDAR", "WRITE_CALENDAR").forEach { put(P + it, PermCategory.CALENDAR) }
        listOf("BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "ACTIVITY_RECOGNITION")
            .forEach { put(P + it, PermCategory.BODY) }
        listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "NEARBY_WIFI_DEVICES")
            .forEach { put(P + it, PermCategory.NEARBY) }
        listOf("SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE",
            "QUERY_ALL_PACKAGES")
            .forEach { put(P + it, PermCategory.SPECIAL) }
    }

    private val sensitiveGroup = setOf(
        PermCategory.CAMERA, PermCategory.MICROPHONE, PermCategory.LOCATION,
        PermCategory.CONTACTS, PermCategory.SMS, PermCategory.PHONE
    )

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<AppPerm> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }.distinct()

        return pkgs.mapNotNull { pkg ->
            runCatching {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                build(pm, pkg, info)
            }.getOrNull()
        }.sortedWith(
            compareByDescending<AppPerm> { it.needsReview }
                .thenByDescending { it.grantedCount }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun build(pm: PackageManager, pkg: String, info: PackageInfo): AppPerm {
        val appInfo = info.applicationInfo
        val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        val isSystem = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        val names = info.requestedPermissions ?: emptyArray()
        val flags = info.requestedPermissionsFlags ?: IntArray(0)

        val items = names.mapIndexedNotNull { i, name ->
            val cat = map[name] ?: return@mapIndexedNotNull null
            val granted: Boolean? =
                if (cat == PermCategory.SPECIAL) null
                else (flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            PermItem(name.substringAfterLast('.'), cat, granted)
        }.sortedBy { it.category.ordinal }

        return AppPerm(label, pkg, isSystem, items, reasons(items))
    }

    private fun reasons(items: List<PermItem>): List<String> {
        fun granted(n: String) = items.any { it.name == n && it.granted == true }
        fun requested(n: String) = items.any { it.name == n }
        val r = mutableListOf<String>()
        if (granted("READ_SMS") || granted("RECEIVE_SMS") || granted("SEND_SMS"))
            r += "Akses SMS aktif"
        if (granted("READ_CALL_LOG")) r += "Dapat membaca log panggilan"
        if (granted("ACCESS_BACKGROUND_LOCATION")) r += "Lokasi aktif di latar belakang"
        if (requested("SYSTEM_ALERT_WINDOW")) r += "Dapat tampil di atas aplikasi lain"
        if (requested("REQUEST_INSTALL_PACKAGES")) r += "Dapat memasang aplikasi lain"
        if (requested("MANAGE_EXTERNAL_STORAGE")) r += "Meminta akses ke semua file"
        val sensitive = items.filter { it.granted == true && it.category in sensitiveGroup }
            .map { it.category }.distinct().size
        if (sensitive >= 4) r += "Banyak kategori sensitif aktif ($sensitive dari 6)"
        return r
    }
}
