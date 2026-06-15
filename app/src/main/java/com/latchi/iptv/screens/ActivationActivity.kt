package com.latchi.iptv.screens

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.SourcePrefs

class ActivationActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var layoutM3u: LinearLayout
    private lateinit var layoutXtream: LinearLayout
    private lateinit var profileNameInput: EditText
    private lateinit var m3uInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var actionButton: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: TextView

    private var mode = "m3u"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.latchi.iptv.utils.TvUtils.applyOrientation(this)
        setContentView(R.layout.activity_activation)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        layoutM3u = findViewById(R.id.layoutM3u)
        layoutXtream = findViewById(R.id.layoutXtream)
        profileNameInput = findViewById(R.id.profileNameInput)
        m3uInput = findViewById(R.id.m3uInput)
        serverInput = findViewById(R.id.serverInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        actionButton = findViewById(R.id.actionButton)
        progressBar = findViewById(R.id.progressBarActivation)
        errorText = findViewById(R.id.txtError)
        titleText = findViewById(R.id.titleText)
        backButton = findViewById(R.id.backButton)

        mode = intent.getStringExtra("mode") ?: "m3u"

        backButton.setOnClickListener { finish() }
        actionButton.setOnClickListener { submit() }

        if (mode == "m3u") {
            layoutM3u.visibility = View.VISIBLE
            layoutXtream.visibility = View.GONE
            titleText.text = "إضافة رابط M3U"
        } else {
            layoutM3u.visibility = View.GONE
            layoutXtream.visibility = View.VISIBLE
            titleText.text = "إضافة Xtream Codes"
        }
    }

    private fun submit() {
        if (mode == "m3u") saveM3u() else saveXtream()
    }

    private fun saveM3u() {
        val name = profileNameInput.text.toString().trim().ifBlank { "Manual M3U" }
        val url = m3uInput.text.toString().trim()
        if (!url.startsWith("http")) return showError("أدخل رابط M3U صحيح")
        SourcePrefs.saveManualM3u(this, name, url)
        goMain()
    }

    private fun saveXtream() {
        val name = profileNameInput.text.toString().trim().ifBlank { "Xtream" }
        val server = serverInput.text.toString().trim()
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString().trim()
        if (server.isBlank() || user.isBlank() || pass.isBlank()) return showError("أدخل جميع بيانات Xtream")
        SourcePrefs.saveManualXtream(this, name, server, user, pass)
        goMain()
    }

    private fun goMain() {
        finish()
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
