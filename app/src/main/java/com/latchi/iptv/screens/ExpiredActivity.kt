package com.latchi.iptv.screens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.R

class ExpiredActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.latchi.iptv.utils.TvUtils.applyOrientation(this)
        setContentView(R.layout.activity_expired)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)
        val message = intent.getStringExtra("message") ?: "Subscription inactive"
        findViewById<TextView>(R.id.messageText).text = message
        findViewById<TextView>(R.id.whatsappButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/213798712450")))
        }
        findViewById<TextView>(R.id.newCodeButton).setOnClickListener {
            startActivity(Intent(this, UserListActivity::class.java))
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
