package com.latchi.iptv.screens

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.latchi.iptv.R
import com.latchi.iptv.utils.TvUtils
import java.io.File

/**
 * Professional in-app updater.
 *
 * The APK URL comes from Google Script / Dashboard, then DownloadManager downloads it
 * inside the app with live progress. When the download reaches 100%, the button turns
 * into "Install Update" and opens Android's package installer using FileProvider.
 */
class UpdatePromptActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private var versionName = ""
    private var versionCode = 0
    private var apkUrl = ""
    private var notes = ""
    private var forceUpdate = true
    private var downloadId = -1L
    private var downloadedUri: Uri? = null
    private var downloadedFile: File? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var primaryButton: TextView? = null
    private var receiver: BroadcastReceiver? = null
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_APK_URL = "apk_url"
        private const val EXTRA_NOTES = "notes"
        private const val EXTRA_FORCE = "force_update"

        fun start(context: Context, versionName: String, versionCode: Int, apkUrl: String, notes: String, force: Boolean) {
            val intent = Intent(context, UpdatePromptActivity::class.java).apply {
                putExtra(EXTRA_VERSION_NAME, versionName)
                putExtra(EXTRA_VERSION_CODE, versionCode)
                putExtra(EXTRA_APK_URL, apkUrl)
                putExtra(EXTRA_NOTES, notes)
                putExtra(EXTRA_FORCE, force)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        com.latchi.iptv.utils.ThemeManager.apply(this)
        versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        apkUrl = intent.getStringExtra(EXTRA_APK_URL).orEmpty()
        notes = intent.getStringExtra(EXTRA_NOTES).orEmpty()
        forceUpdate = intent.getBooleanExtra(EXTRA_FORCE, true)
        buildUi(TvUtils.isTv(this))
    }

    private fun buildUi(isTv: Boolean) {
        val root = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_update_gradient) }
        setContentView(root)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(if (isTv) dp(70) else dp(22), if (isTv) dp(44) else dp(22), if (isTv) dp(70) else dp(22), if (isTv) dp(44) else dp(22))
        }
        scroll.addView(outer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(if (isTv) dp(56) else dp(24), if (isTv) dp(44) else dp(24), if (isTv) dp(56) else dp(24), if (isTv) dp(44) else dp(24))
            background = rounded(0xD0141024.toInt(), 0xFF00A8FF.toInt(), if (isTv) dp(3) else dp(2), if (isTv) dp(34) else dp(24))
            elevation = if (isTv) dp(28).toFloat() else dp(14).toFloat()
        }
        outer.addView(card, LinearLayout.LayoutParams(if (isTv) dp(900) else LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_update_shield)
            contentDescription = "Update"
        }, LinearLayout.LayoutParams(if (isTv) dp(108) else dp(76), if (isTv) dp(108) else dp(76)))

        card.addView(TextView(this).apply {
            text = "تحديث جديد ضروري"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = if (isTv) 36f else 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "LATCHI IPTV  ${versionName.ifBlank { "نسخة جديدة" }}"
            setTextColor(Color.WHITE)
            textSize = if (isTv) 22f else 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = notes.ifBlank { "تم تجهيز نسخة جديدة أكثر استقراراً. يجب تحميل التحديث للمتابعة." }
            setTextColor(Color.parseColor("#E9DDF8"))
            textSize = if (isTv) 20f else 14f
            gravity = Gravity.CENTER
            setLineSpacing(if (isTv) 8f else 5f, 1.05f)
            setPadding(0, dp(18), 0, dp(4))
        })

        val featureBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(0x77121228, 0x4400E5FF, dp(1), dp(18))
        }
        card.addView(featureBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        listOf(
            "تحديث داخلي بدون فتح المتصفح",
            "شريط تقدم مباشر من 0% إلى 100%",
            "تثبيت آمن عبر نافذة النظام الرسمية"
        ).forEach { txt ->
            featureBox.addView(TextView(this).apply {
                text = txt
                setTextColor(Color.WHITE)
                textSize = if (isTv) 18f else 13f
                setPadding(0, dp(5), 0, dp(5))
            })
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        card.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (isTv) dp(18) else dp(12)).apply { topMargin = dp(22) })

        progressText = TextView(this).apply {
            text = "جاهز للتحميل"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = if (isTv) 18f else 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(progressText)

        primaryButton = TextView(this).apply {
            text = "تحديث الآن / Update Now"
            setTextColor(Color.WHITE)
            textSize = if (isTv) 22f else 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = blueButtonBackground()
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_update_download, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setOnClickListener { startDownload() }
            setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start() }
        }
        card.addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (isTv) dp(70) else dp(56)).apply { topMargin = dp(24) })

        if (!forceUpdate) {
            card.addView(TextView(this).apply {
                text = "لاحقاً"
                setTextColor(Color.WHITE)
                textSize = if (isTv) 17f else 14f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(0, dp(14), 0, 0)
                setOnClickListener { finish() }
            })
        } else {
            card.addView(TextView(this).apply {
                text = "هذا التحديث مطلوب للمتابعة. بعد التحميل اضغط تثبيت."
                setTextColor(Color.parseColor("#C7B7D8"))
                textSize = if (isTv) 16f else 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        }

        primaryButton?.requestFocus()
    }

    private fun startDownload() {
        if (apkUrl.isBlank()) {
            Toast.makeText(this, "رابط التحديث غير متوفر", Toast.LENGTH_LONG).show()
            return
        }
        try {
            progressBar?.visibility = View.VISIBLE
            progressBar?.progress = 0
            progressText?.text = "بدء التحميل... 0%"
            primaryButton?.apply {
                text = "جاري التحميل... 0%"
                isEnabled = false
                alpha = 0.75f
            }

            val safeVersion = versionName.ifBlank { versionCode.toString().ifBlank { "update" } }.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val fileName = "Latchi-IPTV-$safeVersion.apk"
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val file = File(dir, fileName).apply { parentFile?.mkdirs(); if (exists()) delete() }
            downloadedFile = file

            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("LATCHI IPTV")
                .setDescription("تحميل التحديث الجديد")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            registerDownloadReceiver(dm)
            progressHandler.post(object : Runnable {
                override fun run() {
                    updateProgress(dm)
                    if (downloadId != -1L && downloadedUri == null) progressHandler.postDelayed(this, 500)
                }
            })
        } catch (e: Exception) {
            progressText?.text = "تعذر بدء التحميل: ${e.localizedMessage}"
            primaryButton?.apply { isEnabled = true; alpha = 1f; text = "تحديث الآن / Update Now" }
        }
    }

    private fun registerDownloadReceiver(dm: DownloadManager) {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                handleDownloadCompleted(dm)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    private fun handleDownloadCompleted(dm: DownloadManager) {
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) return
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val file = downloadedFile
                downloadedUri = if (file != null && file.exists()) {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } else {
                    dm.getUriForDownloadedFile(downloadId)
                }
                progressBar?.progress = 100
                progressText?.text = "اكتمل التحميل 100%. اضغط تثبيت التحديث."
                primaryButton?.apply {
                    text = "تثبيت التحديث / Install Update"
                    isEnabled = true
                    alpha = 1f
                    background = blueButtonBackground()
                    setOnClickListener { openInstaller() }
                    requestFocus()
                }
            } else if (status == DownloadManager.STATUS_FAILED) {
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                progressText?.text = "فشل التحميل. السبب: $reason"
                primaryButton?.apply { text = "إعادة المحاولة"; isEnabled = true; alpha = 1f; setOnClickListener { startDownload() } }
            } else {
                // Still pending/running/paused; progress polling will continue.
            }
        }
    }

    private fun updateProgress(dm: DownloadManager) {
        if (downloadId == -1L) return
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q)?.use { c ->
            if (c.moveToFirst()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                if (total > 0) {
                    val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                    progressBar?.progress = percent
                    progressText?.text = "جاري التحميل: $percent%"
                    primaryButton?.text = "جاري التحميل... $percent%"
                } else if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                    progressText?.text = "جاري التحميل..."
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) handleDownloadCompleted(dm)
            }
        }
    }

    private fun openInstaller() {
        val uri = downloadedUri
        if (uri == null) {
            Toast.makeText(this, "ملف التحديث غير جاهز", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "فعّل السماح بتثبيت التطبيقات لهذا التطبيق ثم ارجع للتثبيت", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    }

    override fun onBackPressed() {
        if (forceUpdate) {
            Toast.makeText(this, "التحديث مطلوب للمتابعة", Toast.LENGTH_SHORT).show()
        } else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (forceUpdate && keyCode == KeyEvent.KEYCODE_BACK) {
            Toast.makeText(this, "التحديث مطلوب للمتابعة", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        progressHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun rounded(color: Int, strokeColor: Int, strokeWidth: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(strokeWidth, strokeColor)
    }

    private fun blueButtonBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor("#007BFF"), Color.parseColor("#00D4FF"))
    ).apply { cornerRadius = dp(18).toFloat() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
