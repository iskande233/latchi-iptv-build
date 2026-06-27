package com.latchi.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import com.latchi.iptv.screens.*
import com.latchi.iptv.utils.AppModeManager
import com.latchi.iptv.utils.FloatingBackHelper
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.MatchNotificationHelper
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvUtils
import com.latchi.iptv.utils.UpdateChecker

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_AI_VOICE = "open_ai_voice"
    }

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(LocaleHelper.wrap(context))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        ThemeManager.apply(this)
        
        try {
            setContentView(R.layout.activity_main)
            FloatingBackHelper.setup(this)

            val home = HomeFragment().apply {
                arguments = Bundle().apply { putBoolean(EXTRA_OPEN_AI_VOICE, intent.getBooleanExtra(EXTRA_OPEN_AI_VOICE, false)) }
            }
            supportFragmentManager.commit(allowStateLoss = true) {
                setReorderingAllowed(true)
                replace(R.id.contentFrame, home)
            }
            MatchNotificationHelper.startChecking(this)

            UpdateChecker.checkInBackground(this, object : UpdateChecker.OnUpdateListener {
                override fun onUpdateAvailable(info: UpdateChecker.UpdateInfo) {
                    if (info.forceUpdate) {
                        UpdateChecker.showUpdateDialog(this@MainActivity, info)
                    }
                }
            })
        } catch (e: Throwable) {
            Toast.makeText(this, "Crash: ${e.message}", Toast.LENGTH_LONG).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawerLayout = findViewById<DrawerLayout?>(R.id.drawerLayout)
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    return
                }

                if (isPlayerOpen()) {
                    closeAndStopPlayer()
                    return
                }

                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }

                // في الوضع المجاني لا توجد شاشة حسابات/كود؛ الخروج يرجع للخلفية مباشرة.
                if (AppModeManager.isFreeProfile(com.latchi.iptv.utils.SourcePrefs.getActiveProfile(this@MainActivity))) {
                    moveTaskToBack(true)
                    return
                }

                // ⚡ Requirement 3: Exit from lists/channels returns exactly to Root Accounts interface ⚡
                val intent = Intent(this@MainActivity, UserListActivity::class.java).apply { putExtra("show_settings", true) }
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_AI_VOICE, false)) {
            supportFragmentManager.commit(allowStateLoss = true) {
                setReorderingAllowed(true)
                replace(R.id.contentFrame, HomeFragment().apply {
                    arguments = Bundle().apply { putBoolean(EXTRA_OPEN_AI_VOICE, true) }
                })
            }
        }
    }

    private fun isPlayerOpen(): Boolean {
        return false
    }

    private fun closeAndStopPlayer() {
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
