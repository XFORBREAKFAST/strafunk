package com.dzaky.strafunk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dzaky.strafunk.ui.Bus
import com.dzaky.strafunk.ui.StrafunkApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK)
        )
        handle(intent)
        setContent { StrafunkApp(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Tombol "Selesai" di notifikasi membuka app dengan extra sf_action=finish. */
    private fun handle(i: Intent?) {
        when (i?.getStringExtra("sf_action")) {
            "finish" -> { i.removeExtra("sf_action"); Bus.finishReq.value = true }
            "record" -> { i.removeExtra("sf_action"); Bus.openRecordReq.value = true }
        }
    }
}
