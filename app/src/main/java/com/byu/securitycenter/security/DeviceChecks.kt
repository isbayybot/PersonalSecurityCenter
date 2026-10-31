package com.byu.securitycenter.security

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Status { OK, REVIEW, INFO }

data class Check(
    val title: String,
    val status: Status,
    val detail: String,
    val limit: String   // batasan jujur dari pemeriksaan ini
)

object DeviceChecks {

    fun run(ctx: Context): List<Check> = listOf(
        securityPatch(),
        androidVersion(),
        screenLock(ctx),
        encryption(ctx),
        developerOptions(ctx),
        usbDebugging(ctx)
    )

    /** Skor = persentase pemeriksaan berstatus OK dari yang bukan INFO. Bukan jaminan keamanan. */
    fun score(checks: List<Check>): Int {
        val scored = checks.filter { it.status != Status.INFO }
        if (scored.isEmpty()) return 0
        return scored.count { it.status == Status.OK } * 100 / scored.size
    }

    private fun securityPatch(): Check {
        val raw = Build.VERSION.SECURITY_PATCH
        val months = runCatching {
            ChronoUnit.MONTHS.between(LocalDate.parse(raw), LocalDate.now())
        }.getOrNull()
        val status = when {
            months == null -> Status.INFO
            months > 6 -> Status.REVIEW
            else -> Status.OK
        }
        val detail = if (months == null) "Patch: $raw" else "Patch: $raw ($months bulan lalu)"
        return Check("Patch keamanan", status, detail,
            "Hanya membaca tanggal patch yang dilaporkan pabrikan; tidak memverifikasi isi patch.")
    }

    private fun androidVersion(): Check {
        val sdk = Build.VERSION.SDK_INT
        return Check("Versi Android",
            if (sdk >= 30) Status.OK else Status.REVIEW,
            "Android ${Build.VERSION.RELEASE} (API $sdk)",
            "Versi lama mungkin tidak lagi menerima pembaruan keamanan; kebijakan tiap pabrikan berbeda.")
    }

    private fun screenLock(ctx: Context): Check {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val secure = km.isDeviceSecure
        return Check("Kunci layar",
            if (secure) Status.OK else Status.REVIEW,
            if (secure) "PIN/pola/sandi aktif" else "Tidak ada kunci layar yang aman",
            "Tidak dapat menilai kekuatan PIN/sandi, hanya apakah kunci aman aktif.")
    }

    private fun encryption(ctx: Context): Check {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val active = dpm.storageEncryptionStatus in setOf(
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
        )
        return Check("Enkripsi penyimpanan",
            if (active) Status.OK else Status.REVIEW,
            if (active) "Aktif" else "Tidak aktif / tidak didukung",
            "Perangkat modern umumnya terenkripsi secara bawaan; ini hanya status yang dilaporkan sistem.")
    }

    private fun developerOptions(ctx: Context): Check {
        val on = Settings.Global.getInt(ctx.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        return Check("Opsi pengembang",
            if (on) Status.REVIEW else Status.OK,
            if (on) "Aktif" else "Nonaktif",
            "Aktif bukan berarti berbahaya; hanya menambah permukaan serangan jika tidak dipakai.")
    }

    private fun usbDebugging(ctx: Context): Check {
        val on = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        return Check("Debugging USB",
            if (on) Status.REVIEW else Status.OK,
            if (on) "Aktif" else "Nonaktif",
            "Matikan jika tidak sedang dipakai, terutama sebelum mencolok ke perangkat tak dikenal.")
    }
}
