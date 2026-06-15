package com.latchi.iptv.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvFocusHelper
import com.latchi.iptv.utils.TvUtils

/**
 * شاشة فئات قنوات beIN Sports المفلترة فقط.
 * عند اختيار فئة، تفتح شاشة المعاينة TvLivePreviewActivity لهذه الفئة.
 */
class BeInSportsCategoriesActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyState: TextView

    companion object {
        /**
         * الكلمات المفتاحية التي تحدد قنوات beIN Sports في أي رابط M3U.
         * مرنة لتتعامل مع التسمية العربية والفرنسية والإنجليزية.
         */
        private val BEIN_KEYWORDS = listOf(
            "bein", "be in", "بي ان", "بين", "بي إن",
            "sport", "سبورت", "رياض", "رياضة",
            "ssc", "alkass", "الكاس", "القص",
            "ad sport", "adsports", "ads",
            "kora", "كورة"
        )

        private val CATEGORY_KEYWORDS = mapOf(
            "beIN SPORTS 1 HD" to listOf("sports 1", "bein 1", "سبورت 1", "بي ان 1"),
            "beIN SPORTS 2 HD" to listOf("sports 2", "bein 2", "سبورت 2", "بي ان 2"),
            "beIN SPORTS 3 HD" to listOf("sports 3", "bein 3", "سبورت 3", "بي ان 3"),
            "beIN SPORTS 4 HD" to listOf("sports 4", "bein 4", "سبورت 4", "بي ان 4"),
            "beIN SPORTS 5 HD" to listOf("sports 5", "bein 5", "سبورت 5", "بي ان 5"),
            "beIN SPORTS 6 HD" to listOf("sports 6", "bein 6", "سبورت 6", "بي ان 6"),
            "beIN SPORTS NEWS" to listOf("sports news", "news", "اخبار", "أخبار"),
            "beIN SPORTS XTRA 1" to listOf("xtra 1", "extra 1", "اكسترا"),
            "beIN SPORTS XTRA 2" to listOf("xtra 2", "extra 2"),
            "beIN SPORTS 4K / UHD" to listOf("4k", "uhd"),
            "القنوات الرياضية العربية" to listOf("ssc", "alkass", "الكاس", "adsports", "ad sport"),
        )

        /** يستخرج الفئة بناءً على اسم القناة */
        fun detectCategory(channelName: String): String? {
            val name = channelName.lowercase()
            for ((cat, keys) in CATEGORY_KEYWORDS) {
                if (keys.any { name.contains(it.lowercase()) }) return cat
            }
            // إذا لم تتطابق مع فئة محددة، يكون beIN Sports عام
            if (BEIN_KEYWORDS.any { name.contains(it.lowercase()) }) return "beIN SPORTS (عام)"
            return null
        }

        /** يجمع القنوات حسب الفئة */
        fun groupByCategory(channels: List<Channel>): Map<String, List<Channel>> {
            val groups = mutableMapOf<String, MutableList<Channel>>()
            for (ch in channels) {
                val cat = detectCategory(ch.name)
                if (cat != null) {
                    groups.getOrPut(cat) { mutableListOf() }.add(ch)
                }
            }
            return groups
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadCategories()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050A1A"))
            setPadding(dp(20), dp(14), dp(20), dp(20))
        }
        setContentView(root)

        // ===== Top Bar =====
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(14))
        }
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val back = TextView(this).apply {
            text = "← رجوع"
            setTextColor(Color.parseColor("#7FE6FF"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_vip_grid_card)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        topBar.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))

        val title = TextView(this).apply {
            text = "🏆  beIN Sports • الفئات"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), 0, 0, 0)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // ===== Description card =====
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_vip_bein_card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        infoCard.addView(TextView(this).apply {
            text = "📡 اختر فئة beIN Sports التي تريد مشاهدتها"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        infoCard.addView(TextView(this).apply {
            text = "سيتم فتح شاشة المعاينة الحية للفئة المختارة بكل قنواتها."
            setTextColor(Color.parseColor("#E8D58C"))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(infoCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        // ===== List container (ScrollView manual) =====
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyState = TextView(this).apply {
            text = "⏳ جاري البحث عن قنوات beIN Sports في قائمتك..."
            setTextColor(Color.parseColor("#B8C0E0"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(20))
            visibility = View.VISIBLE
        }
        listContainer.addView(emptyState)

        if (TvUtils.isTv(this)) {
            TvFocusHelper.setupTree(root)
        }
    }

    private fun loadCategories() {
        listContainer.removeAllViews()
        listContainer.addView(TextView(this).apply {
            text = "⏳ جاري البحث عن قنوات beIN Sports في قائمتك..."
            setTextColor(Color.parseColor("#B8C0E0"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(20))
        })

        Thread {
            try {
                val active = SourcePrefs.getActiveProfile(this) ?: return@Thread
                val cached = ChannelCache.load(applicationContext, active.id)
                val groups = groupByCategory(cached)
                runOnUiThread { renderCategories(groups) }
            } catch (e: Throwable) {
                runOnUiThread {
                    listContainer.removeAllViews()
                    listContainer.addView(TextView(this@BeInSportsCategoriesActivity).apply {
                        text = "❌ خطأ في تحميل القنوات:\n${e.localizedMessage}"
                        setTextColor(Color.parseColor("#FF5577"))
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setPadding(dp(20), dp(20), dp(20), dp(20))
                    })
                }
            }
        }.start()
    }

    private fun renderCategories(groups: Map<String, List<Channel>>) {
        listContainer.removeAllViews()

        if (groups.isEmpty()) {
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(40), dp(24), dp(24))
                addView(TextView(this@BeInSportsCategoriesActivity).apply {
                    text = "📭 لا توجد قنوات beIN Sports في قائمتك الحالية"
                    setTextColor(Color.parseColor("#FFB347"))
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@BeInSportsCategoriesActivity).apply {
                    text = "\nاذهب إلى الحسابات وقم بتحديث القائمة\nأو تأكد من أن اشتراكك يحتوي على باقة الرياضة."
                    setTextColor(Color.parseColor("#B8C0E0"))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                })
            })
            return
        }

        // ترتيب الفئات
        val sortedEntries = groups.entries.sortedBy { it.key }

        for ((category, channels) in sortedEntries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_vip_bein_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    openCategoryPreview(category, channels)
                }
                foreground = getDrawable(R.drawable.focus_ring_vip)
            }

            // أيقونة
            val icon = TextView(this).apply {
                text = "📡"
                textSize = 24f
                gravity = Gravity.CENTER
            }
            card.addView(icon, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))

            // النصوص
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            textCol.addView(TextView(this).apply {
                text = category
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            textCol.addView(TextView(this).apply {
                text = "${channels.size} قناة"
                setTextColor(Color.parseColor("#E8D58C"))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // سهم
            val arrow = TextView(this).apply {
                text = "▶"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 18f
                gravity = Gravity.CENTER
            }
            card.addView(arrow, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))

            listContainer.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        if (TvUtils.isTv(this)) {
            TvFocusHelper.setupTree(listContainer.rootView)
            listContainer.postDelayed({
                val first = listContainer.getChildAt(0)
                if (first != null && first.isFocusable) first.requestFocus()
            }, 200)
        }
    }

    private fun openCategoryPreview(category: String, channels: List<Channel>) {
        if (channels.isEmpty()) return
        // فتح شاشة المعاينة بالقنوات المفلترة لهذه الفئة (يتجاوز الفلتر الافتراضي)
        TvLivePreviewActivity.startWithChannels(this, channels.first(), channels, category)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
