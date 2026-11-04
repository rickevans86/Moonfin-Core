package org.moonfin.androidtv.car

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.apps.auto.sdk.CarActivity

/**
 * The Android Auto side of the car screen: a host view that [CarScreen] puts
 * Moonfin-Auto's Flutter view into, plus a message layer for setup, driving
 * and "tap to show".
 */
class MoonfinCarActivity : CarActivity() {
    private lateinit var host: FrameLayout
    private lateinit var message: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIgnoreConfigChanges(-1)
        runCatching { carUiController.statusBarController.hideAppHeader() }

        host = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }
        message = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            isClickable = true
            setOnClickListener {
                if (!CarScreen.driving && CarScreen.hasPermissions(this@MoonfinCarActivity)) {
                    CarScreen.showOnCar(this@MoonfinCarActivity, host)
                }
            }
        }
        val root = FrameLayout(this)
        root.addView(host)
        root.addView(message)
        setContentView(root)

        CarScreen.onStateChanged = { message.post { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        if (CarScreen.hasPermissions(this) && !CarScreen.isAppOnCar) {
            CarScreen.showOnCar(this, host)
        }
        refresh()
    }

    override fun onDestroy() {
        CarScreen.onStateChanged = null
        CarScreen.endSession()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (!CarScreen.dispatchBack()) super.onBackPressed()
    }

    private fun refresh() {
        val text = when {
            !CarScreen.hasPermissions(this) -> {
                CarScreen.markSetupNeeded(this)
                "Finish setting up on your phone.\n\nOpen Moonfin-Auto on your phone and allow " +
                    "Location. It's used only to pause video while the car is moving."
            }
            CarScreen.driving -> "Video is paused while the car is moving."
            !CarScreen.isAppOnCar -> "Moonfin-Auto is open on your phone.\n\nTap to show it here." +
                (CarScreen.lastError?.let { "\n\n($it)" } ?: "")
            else -> null
        }
        message.text = text ?: ""
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }
}
