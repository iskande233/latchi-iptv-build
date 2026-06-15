package com.latchi.iptv.screens

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.SourcePrefs

class ManualSourceActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var sourceTypeGroup: RadioGroup
    private lateinit var layoutM3u: LinearLayout
    private lateinit var layoutXtream: LinearLayout
    private lateinit var editProfileName: EditText
    private lateinit var editM3uUrl: EditText
    private lateinit var editServerUrl: EditText
    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.latchi.iptv.utils.TvUtils.applyOrientation(this)
        setContentView(R.layout.activity_manual_source)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        sourceTypeGroup = findViewById(R.id.sourceTypeGroup)
        layoutM3u = findViewById(R.id.layoutM3u)
        layoutXtream = findViewById(R.id.layoutXtream)
        editProfileName = findViewById(R.id.editProfileName)
        editM3uUrl = findViewById(R.id.editM3uUrl)
        editServerUrl = findViewById(R.id.editServerUrl)
        editUsername = findViewById(R.id.editUsername)
        editPassword = findViewById(R.id.editPassword)
        errorText = findViewById(R.id.txtError)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        sourceTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioM3u) {
                layoutM3u.visibility = View.VISIBLE
                layoutXtream.visibility = View.GONE
            } else {
                layoutM3u.visibility = View.GONE
                layoutXtream.visibility = View.VISIBLE
            }
        }
        findViewById<Button>(R.id.saveManualButton).setOnClickListener { saveManual() }
    }

    private fun saveManual() {
        val name = editProfileName.text.toString().trim().ifBlank { "Manual User" }
        if (sourceTypeGroup.checkedRadioButtonId == R.id.radioM3u) {
            val url = editM3uUrl.text.toString().trim()
            if (!url.startsWith("http")) return showError("Enter valid M3U URL")
            SourcePrefs.saveManualM3u(this, name, url)
        } else {
            val server = editServerUrl.text.toString().trim()
            val username = editUsername.text.toString().trim()
            val password = editPassword.text.toString().trim()
            if (server.isBlank() || username.isBlank() || password.isBlank()) return showError("Enter Xtream details")
            SourcePrefs.saveManualXtream(this, name, server, username, password)
        }
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
