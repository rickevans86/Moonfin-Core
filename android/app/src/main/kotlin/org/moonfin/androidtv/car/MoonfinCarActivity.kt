package org.moonfin.androidtv.car

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.apps.auto.sdk.CarActivity

/**
 * The Android Auto side of the car screen: a surface that [CarScreen] draws
 * Moonfin-Auto into, plus a message layer for setup, driving and "tap to show".
 */
class MoonfinCarActivity : CarActivity(), SurfaceHolder.Callback {
    private lateinit var message: TextView
    private var holder: SurfaceHolder? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIgnoreConfigChanges(-1)
        runCatching { carUiController.statusBarController.hideAppHeader() }

        val surface = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            holder.addCallback(this@MoonfinCarActivity)
        }
        message = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setOnClickListener {
                if (!CarScreen.driving && CarScreen.hasPermissions(this@MoonfinCarActivity)) {
                    CarScreen.launchOnCar(this@MoonfinCarActivity)
                }
            }
        }
        val root = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
                message.visibility != View.VISIBLE

            override fun onTouchEvent(event: MotionEvent): Boolean =
                CarScreen.dispatchTouch(event) || true
        }
        root.addView(surface)
        root.addView(message)
        setContentView(root)

        CarScreen.onStateChanged = { runOnUi { refresh() } }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        CarScreen.onStateChanged = null
        CarScreen.endSession(this)
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
                    "Location (used only to pause video while driving) and " +
                    "Display over other apps."
            }
            CarScreen.driving -> "Video is paused while the car is moving."
            !CarScreen.isAppOnCar -> "Moonfin-Auto is open on your phone.\n\nTap to show it here." +
                (CarScreen.lastError?.let { "\n\n($it)" } ?: "")
            else -> null
        }
        message.text = text ?: ""
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        // Permissions granted on the phone while this screen was up.
        val h = holder
        if (h != null && !CarScreen.hasSession && CarScreen.hasPermissions(this)) {
            show(h, surfaceWidth, surfaceHeight)
        }
    }

    private fun runOnUi(block: () -> Unit) {
        message.post(block)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        show(holder, frame.width(), frame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        show(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        this.holder = null
        CarScreen.surfaceGone()
    }

    private fun show(holder: SurfaceHolder, width: Int, height: Int) {
        this.holder = holder
        surfaceWidth = width
        surfaceHeight = height
        if (width <= 0 || height <= 0 || !CarScreen.hasPermissions(this)) return
        CarScreen.showOn(this, holder.surface, width, height, resources.displayMetrics.densityDpi)
    }
}
