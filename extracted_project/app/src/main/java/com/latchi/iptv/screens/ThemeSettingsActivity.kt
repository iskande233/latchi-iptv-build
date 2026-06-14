package com.latchi.iptv.screens

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.ErrorOverlayHelper
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.ThemeManager

class ThemeSettingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.latchi.iptv.utils.TvUtils.applyOrientation(this)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_theme_settings)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.themeDefault).setOnClickListener {
            ThemeManager.setTheme(this, "default")
            ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.theme_saved))
            recreate()
        }

        findViewById<LinearLayout>(R.id.themeDark).setOnClickListener {
            ThemeManager.setTheme(this, "dark")
            ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.theme_saved))
            recreate()
        }

        findViewById<LinearLayout>(R.id.themeSpace).setOnClickListener {
            ThemeManager.setTheme(this, "space")
            ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.theme_saved))
            recreate()
        }

        findViewById<LinearLayout>(R.id.themeNature).setOnClickListener {
            ThemeManager.setTheme(this, "nature")
            ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.theme_saved))
            recreate()
        }

        findViewById<LinearLayout>(R.id.themeCustom).setOnClickListener {
            pickCustomImage()
        }
    }

    private fun pickCustomImage() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, 2001)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!.toString()
            ThemeManager.setCustomTheme(this, uri)
            ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.theme_saved))
            recreate()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
