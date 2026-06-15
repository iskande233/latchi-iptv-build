package com.latchi.iptv.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.IptvProfile
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvFocusHelper
import com.latchi.iptv.utils.TvUtils

/**
 * شاشة الحسابات VIP الجديدة — مخصصة حصرياً لعرض وإدارة "أكواد التفعيل".
 *
 * لا تقبل ولا تعرض خيارات Xtream / M3U.
 * نظيفة، بسيطة، أنيقة — بأسلوب Royal Dark.
 */
class VipAccountsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var accountsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderAccounts()
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
            setTextColor(Color.parseColor("#39FF8B"))
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
            text = "🔑  الحسابات • VIP"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), 0, 0, 0)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // ===== Description card =====
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_vip_accounts_card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        infoCard.addView(TextView(this).apply {
            text = "🛡️ أكواد التفعيل المسجلة على جهازك"
            setTextColor(Color.parseColor("#39FF8B"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        infoCard.addView(TextView(this).apply {
            text = "هنا تجد أكوادك فقط — لا روابط Xtream، لا روابط M3U. الحسابات نظيفة ومأمونة."
            setTextColor(Color.parseColor("#B8C0E0"))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(infoCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        // ===== Accounts list container =====
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
        }
        accountsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        scroll.addView(accountsContainer)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ===== Add code button (bottom) =====
        val addBtn = TextView(this).apply {
            text = "➕ إضافة كود تفعيل جديد"
            setTextColor(Color.parseColor("#0A0F2C"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_vip_account_active)
            isClickable = true
            isFocusable = true
            foreground = getDrawable(R.drawable.focus_ring_vip)
            setOnClickListener { openAddActivationCodeDialog() }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(addBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(12) })

        if (TvUtils.isTv(this)) {
            TvFocusHelper.setupTree(root)
        }
    }

    private fun renderAccounts() {
        accountsContainer.removeAllViews()
        val profiles = SourcePrefs.getProfiles(this)
        val active = SourcePrefs.getActiveProfile(this)

        if (profiles.isEmpty()) {
            accountsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(40), dp(24), dp(24))
                addView(TextView(this@VipAccountsActivity).apply {
                    text = "📭 لا توجد أكواد تفعيل"
                    setTextColor(Color.parseColor("#FFB347"))
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@VipAccountsActivity).apply {
                    text = "\nاضغط على زر 'إضافة كود تفعيل جديد' في الأسفل"
                    setTextColor(Color.parseColor("#B8C0E0"))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                })
            })
            return
        }

        for (profile in profiles) {
            val isActive = active?.id == profile.id
            accountsContainer.addView(buildAccountCard(profile, isActive), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }

        if (TvUtils.isTv(this)) {
            TvFocusHelper.setupTree(accountsContainer.rootView)
            accountsContainer.postDelayed({
                val first = accountsContainer.getChildAt(0)
                if (first != null && first.isFocusable) first.requestFocus()
            }, 200)
        }
    }

    private fun buildAccountCard(profile: IptvProfile, isActive: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (isActive) R.drawable.bg_vip_account_active else R.drawable.bg_vip_account)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            foreground = getDrawable(R.drawable.focus_ring_vip)
            setOnClickListener { onAccountClicked(profile, isActive) }
        }

        // ===== الصف الأول: الاسم + الحالة =====
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameTxt = TextView(this).apply {
            text = if (isActive) "✅ ${profile.name}" else "👤 ${profile.name}"
            setTextColor(Color.parseColor("#0A0F2C"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        headerRow.addView(nameTxt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (isActive) {
            headerRow.addView(TextView(this).apply {
                text = "نشط"
                setTextColor(Color.parseColor("#0A0F2C"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        card.addView(headerRow)

        // ===== كود التفعيل فقط (لا نعرض روابط M3U/Xtream) =====
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        codeRow.addView(TextView(this).apply {
            text = "🔑 كود التفعيل:"
            setTextColor(if (isActive) Color.parseColor("#0A0F2C") else Color.parseColor("#7FE6FF"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
        })
        codeRow.addView(TextView(this).apply {
            text = profile.activationCode
            setTextColor(if (isActive) Color.parseColor("#0A0F2C") else Color.parseColor("#F2F4FF"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // زر نسخ سريع
        codeRow.addView(TextView(this).apply {
            text = "📋"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            isClickable = true
            setOnClickListener {
                copyToClipboard("كود التفعيل", profile.activationCode)
            }
        })
        card.addView(codeRow)

        // ===== تاريخ الصلاحية =====
        if (profile.expiresAt.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "📅 ينتهي في: ${profile.expiresAt}"
                setTextColor(if (isActive) Color.parseColor("#0A0F2C") else Color.parseColor("#B8C0E0"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
        }

        // ===== أزرار التحكم (تحويل + حذف) =====
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }

        if (!isActive) {
            actionsRow.addView(TextView(this).apply {
                text = "🔄  تحويل للحساب"
                setTextColor(if (isActive) Color.parseColor("#0A0F2C") else Color.parseColor("#39FF8B"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener { activateAccount(profile) }
            })
        }

        actionsRow.addView(TextView(this).apply {
            text = "🗑️  حذف"
            setTextColor(if (isActive) Color.parseColor("#0A0F2C") else Color.parseColor("#FF5577"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { deleteAccount(profile) }
        })

        card.addView(actionsRow)
        return card
    }

    private fun onAccountClicked(profile: IptvProfile, isActive: Boolean) {
        if (!isActive) {
            activateAccount(profile)
        } else {
            copyToClipboard("كود التفعيل", profile.activationCode)
        }
    }

    private fun activateAccount(profile: IptvProfile) {
        SourcePrefs.setActiveProfile(this, profile.id)
        Toast.makeText(this, "✅ تم التحويل إلى: ${profile.name}", Toast.LENGTH_SHORT).show()
        renderAccounts()
    }

    private fun deleteAccount(profile: IptvProfile) {
        AlertDialog.Builder(this)
            .setTitle("تأكيد الحذف")
            .setMessage("هل تريد حذف كود التفعيل '${profile.name}' نهائياً؟")
            .setPositiveButton("نعم، احذف") { _, _ ->
                SourcePrefs.deleteProfile(this, profile.id)
                Toast.makeText(this, "🗑️ تم الحذف", Toast.LENGTH_SHORT).show()
                renderAccounts()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun copyToClipboard(label: String, value: String) {
        try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(this, "✓ تم نسخ $label", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun openAddActivationCodeDialog() {
        // فتح شاشة إضافة الكود الحالية (بدون M3U/Xtream)
        val intent = Intent(this, ActivationActivity::class.java)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        renderAccounts()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
