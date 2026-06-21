package com.latchi.iptv.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.R
import com.latchi.iptv.adapter.ChannelsAdapter
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.CatalogRepository
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.DigitNormalizer
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.PlayerActivity
import com.latchi.iptv.utils.RemoteViewConfigPrefs
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🏆 BeinSportsActivity — واجهة beIN Sports احترافية مخصصة للهاتف
 *
 * تعرض **فقط** قنوات beIN + beIN MAX + ALWAN (بدون خلط مع قنوات أخرى).
 * مرتبة بحيث:
 *   - كأس العالم في الأعلى
 *   - beIN MAX ثم beIN ثم ALWAN
 *
 * تختلف عن TvLivePreviewActivity:
 *   - مخصصة للهاتف (لا لوحة تلفزيون)
 *   - واجهة عمودية (قائمة طويلة)
 *   - بدون لوحة فئات (لأن كل شيء beIN)
 */
class BeinSportsActivity : AppCompatActivity() {

    private lateinit var channelsRecycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var headerText: TextView
    private lateinit var countText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: ChannelsAdapter

    private var allBeinChannels: List<Channel> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadBeinChannels()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        // تم تعطيل rotation للتلفزيون فقط — الهاتف يبقى portrait
        TvUtils.applyOrientation(this)

        // ====== Top bar ======
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A0B2E"))
            setPadding(dp(16), dp(20), dp(16), dp(20))
            elevation = dp(8).toFloat()
        }

        val backBtn = TextView(this).apply {
            text = "← رجوع"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        topBar.addView(TextView(this).apply {
            text = "beIN SPORTS"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(20), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(topBar)

        // ====== Header card مع صورة beIN ======
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.phone_card_bein)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180)
            )
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        headerText = TextView(this).apply {
            text = "🏆 قنوات beIN Sports الحصرية"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#9D4EDD"))
        }
        headerCard.addView(headerText)
        countText = TextView(this).apply {
            text = "⏳ جاري التحميل..."
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
            setShadowLayer(6f, 0f, 0f, Color.parseColor("#9D4EDD"))
        }
        headerCard.addView(countText)
        root.addView(headerCard)

        // ====== Progress ======
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4)
            )
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9D4EDD"))
        }
        root.addView(progressBar)

        // ====== Empty state (إذا لا توجد قنوات) ======
        emptyText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8888AA"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(20))
            visibility = View.GONE
        }
        root.addView(emptyText)

        // ====== Channels list ======
        channelsRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BeinSportsActivity)
            setPadding(0, dp(8), 0, dp(8))
            setBackgroundColor(Color.parseColor("#0A0518"))
        }
        root.addView(channelsRecycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply { weight = 1f })
    }

    private fun loadBeinChannels() {
        val active = SourcePrefs.getActiveProfile(this)
        if (active == null) {
            showEmpty("⚠️ لا يوجد حساب مفعّل. اذهب إلى الإعدادات لتنشيط حساب.")
            return
        }

        progressBar.visibility = View.VISIBLE
        countText.text = "⏳ جاري جلب قنوات beIN..."

        // جلب القنوات من Room أولاً (Offline-First)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val liveChannels = CatalogRepository.getChannelsByTypeBlocking(this@BeinSportsActivity, active.id, "live")
                val beinFiltered = filterBeinSports(liveChannels)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (beinFiltered.isEmpty()) {
                        // إذا لا توجد بيانات محفوظة، جلب من السيرفر
                        loadFromServer(active.id)
                    } else {
                        allBeinChannels = beinFiltered
                        displayChannels()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    loadFromServer(active.id)
                }
            }
        }
    }

    private fun loadFromServer(profileId: String) {
        countText.text = "🌐 جاري الاتصال بالسيرفر..."
        // إعادة التحميل من السيرفر
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val remoteConfig = RemoteViewConfigPrefs.getFilterConfig(this@BeinSportsActivity, profileId)
                CatalogRepository.syncNowBlocking(this@BeinSportsActivity, SourcePrefs.getActiveProfile(this@BeinSportsActivity)!!, onlyType = "live")
                val liveChannels = CatalogRepository.getChannelsByTypeBlocking(this@BeinSportsActivity, profileId, "live")
                val beinFiltered = filterBeinSports(liveChannels)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    allBeinChannels = beinFiltered
                    displayChannels()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showEmpty("❌ تعذر جلب القنوات:\n${e.message?.take(100) ?: "خطأ غير معروف"}")
                }
            }
        }
    }

    /**
     * 🛡️ فلتر احترافي لقنوات beIN Sports:
     *   1. World Cup في الأعلى
     *   2. beIN MAX
     *   3. beIN العادي
     *   4. ALWAN
     * مع إزالة التكرارات.
     */
    private fun filterBeinSports(channels: List<Channel>): List<Channel> {
        val remoteConfig = RemoteViewConfigPrefs.getFilterConfig(this, SourcePrefs.getActiveProfile(this)?.id ?: "")
        val beinTokens = remoteConfig.beinKeywords
        val beinMaxTokens = remoteConfig.beinMaxKeywords
        val alwanTokens = remoteConfig.alwanKeywords

        fun normalized(ch: Channel): String = DigitNormalizer.normalizeDigits("${ch.name} ${ch.category}").lowercase()
        fun hasAny(text: String, tokens: List<String>): Boolean = tokens.any { token -> text.contains(token.lowercase()) }
        fun isBein(text: String): Boolean = hasAny(text, beinTokens)
        fun isBeinMax(text: String): Boolean = hasAny(text, beinTokens) && hasAny(text, beinMaxTokens)
        fun isAlwan(text: String): Boolean = hasAny(text, alwanTokens)
        fun isWorldCup(text: String): Boolean =
            text.contains("world cup") || text.contains("كاس العالم") ||
            text.contains("كأس العالم") || text.contains("كاس العالم") ||
            text.contains("fifa") || text.contains("wc ")
        fun firstNumber(text: String): Int = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 999

        return channels
            .filter { ch ->
                val text = normalized(ch)
                isBein(text) || isAlwan(text) || isWorldCup(text)
            }
            .distinctBy { it.streamUrl.ifBlank { it.name } }
            .sortedWith(
                compareBy<Channel> { ch ->
                    val text = normalized(ch)
                    when {
                        isWorldCup(text) -> 0
                        isBeinMax(text) -> 1
                        isBein(text) -> 2
                        isAlwan(text) -> 3
                        else -> 4
                    }
                }.thenBy { ch ->
                    val text = normalized(ch)
                    firstNumber(text)
                }.thenBy { ch -> normalized(ch) }
            )
    }

    private fun displayChannels() {
        if (allBeinChannels.isEmpty()) {
            showEmpty("📭 لا توجد قنوات beIN حالياً.\n\nتأكد من أن:\n• السيرفر يحتوي على قنوات beIN\n• كلمات beIN keywords صحيحة في الإعدادات")
            return
        }

        emptyText.visibility = View.GONE
        countText.text = "📺 ${allBeinChannels.size} قناة beIN متاحة"

        adapter = ChannelsAdapter(
            allBeinChannels,
            isGrid = false,
            isFavorite = { /* placeholder */ false },
            onFavoriteClicked = { /* no-op */ },
            onChannelClicked = { ch ->
                try {
                    PlayerActivity.start(this, ch)
                } catch (e: Exception) {
                    Toast.makeText(this, "❌ خطأ في تشغيل القناة: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
        channelsRecycler.adapter = adapter
    }

    private fun showEmpty(message: String) {
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
        countText.text = "📭 0 قناة متاحة"
        channelsRecycler.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // إعادة تحميل عند العودة (في حال تم تحديث القنوات في الخلفية)
        loadBeinChannels()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
