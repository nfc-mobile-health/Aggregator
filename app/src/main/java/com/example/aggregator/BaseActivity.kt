package com.example.aggregator

import android.content.Intent
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    override fun onStart() {
        super.onStart()
        findViewById<ImageButton?>(R.id.homeBtn)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
    }
}