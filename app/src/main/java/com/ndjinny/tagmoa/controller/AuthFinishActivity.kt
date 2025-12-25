package com.ndjinny.tagmoa.controller

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AuthFinishActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeToLogin()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        routeToLogin()
    }

    private fun routeToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
        overridePendingTransition(0, 0)
    }
}
