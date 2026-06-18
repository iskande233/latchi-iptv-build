package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.adapter.UserProfilesAdapter
import com.latchi.iptv.utils.ActivationConfig
import com.latchi.iptv.utils.ActivationValidator
import com.latchi.iptv.utils.FloatingBackHelper
import com.latchi.iptv.utils.IptvProfile
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvUtils
import kotlin.concurrent.thread

class UserListActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var codeInput: EditText
    private lateinit var activateCodeButton: TextView
    private lateinit var addXtreamButton: TextView
    private lateinit var addM3uButton: TextView
    private lateinit var noCodeButton: TextView

    private lateinit var accountsSection: LinearLayout
    private lateinit var accountsRecyclerView: RecyclerView
    private lateinit var playButton: TextView
    private lateinit var accountsAdapter: UserProfilesAdapter
    private lateinit var additionToolsSection: LinearLayout
    private lateinit var drawerLayout: DrawerLayout

    private var activeProfile: IptvProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_user_list)

        // 🎯 Dual Universal Permanent Dual Floating Back Buttons 🎯
        FloatingBackHelper.setup(this)

        codeInput = findViewById(R.id.codeInput)
        activateCodeButton = findViewById(R.id.activateCodeButton)
        addXtreamButton = findViewById(R.id.addXtreamButton)
        addM3uButton = findViewById(R.id.addM3uButton)
        noCodeButton = findViewById(R.id.noCodeButton)

        accountsSection = findViewById(R.id.accountsSection)
        accountsRecyclerView = findViewById(R.id.accountsRecyclerView)
        playButton = findViewById(R.id.playButton)
        additionToolsSection = findViewById(R.id.additionToolsSection)
        drawerLayout = findViewById(R.id.drawerLayout)

        setupDrawer()

        accountsAdapter = UserProfilesAdapter(emptyList(), activeId = null, onOpen = { profile ->
            SourcePrefs.setActiveProfile(this, profile.id)
            showLoadingThenGoToMain()
        }, onDelete = { profile ->
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_delete))
                .setMessage(getString(R.string.delete_profile_confirm, profile.name))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    SourcePrefs.deleteProfile(this, profile.id)
                    refreshScreen()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        })
        accountsRecyclerView.layoutManager = LinearLayoutManager(this)
        accountsRecyclerView.adapter = accountsAdapter

        activateCodeButton.setOnClickListener { activateCode() }
        
        codeInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if ((s?.length ?: 0) >= 6) {
                    activateCodeButton.requestFocus()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        addXtreamButton.setOnClickListener { openAddXtream() }
        addM3uButton.setOnClickListener { openAddM3u() }
        noCodeButton.setOnClickListener { openWhatsApp() }

        playButton.setOnClickListener {
            activeProfile?.let { profile ->
                SourcePrefs.setActiveProfile(this, profile.id)
                showLoadingThenGoToMain()
            } ?: Toast.makeText(this, "يرجى تحديد حساب أو تفعيل اشتراك", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshScreen()
    }

    private fun setupDrawer() {
        findViewById<TextView>(R.id.menuButton)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
        findViewById<TextView>(R.id.drawerMatches)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, MatchesActivity::class.java))
        }
        findViewById<TextView>(R.id.drawerPrayer)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, PrayerActivity::class.java))
        }
        findViewById<TextView>(R.id.drawerPricing)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, PricingActivity::class.java))
        }
        findViewById<TextView>(R.id.drawerThemeSettings)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.drawerSettings)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.drawerAbout)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            showAboutDialog()
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_info_title))
            .setMessage(getString(R.string.app_info_desc))
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun refreshScreen() {
        activeProfile = SourcePrefs.getActiveProfile(this)

        val profiles = SourcePrefs.getProfiles(this)
        accountsAdapter.update(profiles, activeProfile?.id)

        accountsSection.visibility = if (profiles.isNotEmpty()) View.VISIBLE else View.GONE

        refreshScreen()

        // 👑 توجيه الفوكس المباشر إلى زر دخول
        if (profiles.isNotEmpty() || codeInput.text.isNotBlank()) {
            activateCodeButton.postDelayed({ activateCodeButton.requestFocus() }, 100L)
        } else {
            codeInput.postDelayed({ codeInput.requestFocus() }, 100L)
        }

        additionToolsSection.visibility = View.VISIBLE
    }

    private fun showLoadingThenGoToMain() {
        setContentView(R.layout.activity_loading)
        val progressBar = findViewById<ProgressBar>(R.id.loadingProgress)
        val percentText = findViewById<TextView>(R.id.loadingPercent)
        val statusText = findViewById<TextView>(R.id.loadingText)

        statusText.text = "LOADING ..."

        Handler(Looper.getMainLooper()).postDelayed({
            var progress = 0
            val runnable = object : Runnable {
                override fun run() {
                    progress += 20
                    progressBar.progress = progress
                    percentText.text = "$progress%"
                    if (progress < 100) {
                        Handler(Looper.getMainLooper()).postDelayed(this, 400)
                    } else {
                        val verified = activeProfile?.let {
                            getSharedPreferences("verification_prefs", MODE_PRIVATE).getBoolean("is_verified_${it.id}", false)
                        } ?: false
                        val nextIntent = if (activeProfile?.activationCode == "MANUAL" || verified) {
                            Intent(this@UserListActivity, MainActivity::class.java)
                        } else {
                            Intent(this@UserListActivity, VerificationActivity::class.java)
                        }
                        nextIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(nextIntent)
                        finish()
                    }
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(runnable, 400)
        }, 200)
    }

    private fun openWhatsApp() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/213798712450?text=مرحبا، أريد شراء كود تفعيل لتطبيق LATCHI IPTV")))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.whatsapp_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAddM3u() {
        val intent = Intent(this, ActivationActivity::class.java)
        intent.putExtra("mode", "m3u")
        startActivity(intent)
    }

    private fun openAddXtream() {
        val intent = Intent(this, ActivationActivity::class.java)
        intent.putExtra("mode", "xtream")
        startActivity(intent)
    }

    private fun activateCode() {
        val code = codeInput.text.toString().trim()
        if (code.length < 4) {
            Toast.makeText(this, getString(R.string.invalid_code), Toast.LENGTH_SHORT).show()
            return
        }
        if (ActivationConfig.ACTIVATION_API_URL.contains("PASTE_GOOGLE")) {
            Toast.makeText(this, getString(R.string.api_not_configured), Toast.LENGTH_SHORT).show()
            return
        }

        activateCodeButton.isEnabled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.checking_subscription))
            .setView(ProgressBar(this))
            .setCancelable(false)
            .create()
        dialog.show()

        thread {
            try {
                val result = ActivationValidator.validateCode(this, code)
                runOnUiThread {
                    dialog.dismiss()
                    activateCodeButton.isEnabled = true
                    if (result.success) {
                        SourcePrefs.saveActivatedProfile(
                            context = this,
                            code = code,
                            name = result.name.ifBlank { "User $code" },
                            playlistUrl = result.playlistUrl,
                            expiresAt = result.expiresAt,
                            maxDevices = result.maxDevices
                        )
                        refreshScreen()
                        Toast.makeText(this, "تمت إضافة الحساب بنجاح ✓", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, result.message.ifBlank { getString(R.string.invalid_code) }, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    activateCodeButton.isEnabled = true
                    Toast.makeText(this, e.localizedMessage ?: getString(R.string.activation_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var backPressedTime = 0L

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            if (System.currentTimeMillis() - backPressedTime < 2000) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.confirm_exit_title))
                    .setMessage(getString(R.string.confirm_exit_desc))
                    .setPositiveButton("موافق") { _, _ -> finishAffinity() }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                backPressedTime = System.currentTimeMillis()
                Toast.makeText(this, getString(R.string.press_back_again_exit), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
