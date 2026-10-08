package com.sysadmindoc.alarmclock.ui.externalalert

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.service.ExternalAlertService
import java.lang.ref.WeakReference

/** Full-screen alert over the lock screen: the message and one big Stop button. */
class ExternalAlertActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1630"))
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.ext_alert_title)
            setTextColor(Color.parseColor("#6EA8FF"))
            textSize = 16f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
                .ifBlank { getString(R.string.ext_alert_default_message) }
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad * 2)
        })
        root.addView(Button(this).apply {
            text = getString(R.string.ext_alert_stop)
            textSize = 20f
            setOnClickListener {
                ExternalAlertService.stop(this@ExternalAlertActivity)
                finish()
            }
        })
        setContentView(root)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private var current: WeakReference<ExternalAlertActivity>? = null

        fun intent(context: Context, text: String): Intent =
            Intent(context, ExternalAlertActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun close() {
            current?.get()?.finish()
            current = null
        }
    }
}
