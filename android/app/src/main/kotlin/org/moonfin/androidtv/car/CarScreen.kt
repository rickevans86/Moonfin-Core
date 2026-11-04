package org.moonfin.androidtv.car

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.ryanheise.audioservice.AudioServicePlugin
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel
import org.moonfin.androidtv.MainActivity
import java.lang.ref.WeakReference

/**
 * Shows Moonfin-Auto's own screen on the Android Auto display.
 *
 * Android Auto gives [MoonfinCarActivity] a surface on the car screen. A private
 * virtual display draws into that surface, and MainActivity's task is moved
 * onto the virtual display, so the full app (including the video player) runs
 * there. Touches on the car screen are handed straight to MainActivity, which
 * lives in this process.
 *
 * Video must only play while parked. The phone's GPS speed decides that: once
 * the car moves, the car screen is covered and Dart is told to pause video.
 */
object CarScreen {
    private const val TAG = "MoonfinCar"
    private const val PREFS = "moonfin_car"
    private const val KEY_SETUP_NEEDED = "setup_needed"
    private const val CAR_CHANNEL = "org.moonfin.androidtv/car"

    // Above ~11 km/h counts as driving; the car must then stay under ~4 km/h
    // for a few seconds before it counts as parked again.
    private const val DRIVING_SPEED_MPS = 3f
    private const val PARKED_SPEED_MPS = 1.1f
    private const val PARKED_DELAY_MS = 4000L

    private val handler = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var mainActivity = WeakReference<Activity>(null)
    private var mainActivityDisplayId = Display.DEFAULT_DISPLAY
    private var locationManager: LocationManager? = null
    private var pendingParked: Runnable? = null

    /** Called whenever what the car screen should show may have changed. */
    var onStateChanged: (() -> Unit)? = null

    var driving = false
        private set

    val hasSession: Boolean
        get() = virtualDisplay != null

    val isAppOnCar: Boolean
        get() {
            val id = virtualDisplay?.display?.displayId ?: return false
            return mainActivity.get() != null && mainActivityDisplayId == id
        }

    fun isCarDisplay(displayId: Int): Boolean =
        virtualDisplay?.display?.displayId == displayId

    fun hasPermissions(ctx: Context): Boolean =
        Settings.canDrawOverlays(ctx) &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---- Car session -------------------------------------------------------

    /** The car screen's surface is ready (or changed size). */
    fun showOn(ctx: Context, surface: Surface, width: Int, height: Int, dpi: Int) {
        val app = ctx.applicationContext
        val vd = virtualDisplay
        if (vd == null) {
            val dm = app.getSystemService(DisplayManager::class.java)
            virtualDisplay = dm.createVirtualDisplay(
                "Moonfin-Auto car screen", width, height, dpi, surface, 0,
            )
            startDrivingMonitor(app)
            launchOnCar(app)
        } else {
            vd.resize(width, height, dpi)
            vd.surface = surface
            if (!isAppOnCar) launchOnCar(app)
        }
        onStateChanged?.invoke()
    }

    /** The car screen's surface went away; the session may come back. */
    fun surfaceGone() {
        virtualDisplay?.surface = null
    }

    /** Android Auto closed Moonfin-Auto or disconnected. */
    fun endSession(ctx: Context) {
        val app = ctx.applicationContext
        val vd = virtualDisplay ?: return
        stopDrivingMonitor()
        setDriving(false)
        // A private virtual display destroys whatever is on it when released,
        // and MainActivity ends the whole process when it's destroyed, so move
        // it back to the phone first and keep it out of the way there.
        if (isAppOnCar) {
            launch(app, Display.DEFAULT_DISPLAY)
            handler.postDelayed({ mainActivity.get()?.moveTaskToBack(true) }, 600)
        }
        handler.postDelayed({
            vd.release()
            if (virtualDisplay === vd) virtualDisplay = null
        }, 1500)
    }

    /** Puts MainActivity on the car screen, moving it off the phone if needed. */
    fun launchOnCar(ctx: Context) {
        val id = virtualDisplay?.display?.displayId ?: return
        if (driving) return
        val activity = mainActivity.get()
        // Android only moves a task between displays when it isn't already the
        // front task, so step it back on the phone first.
        if (activity != null && mainActivityDisplayId != id && !activity.isFinishing) {
            activity.moveTaskToBack(true)
            handler.postDelayed({ launch(ctx.applicationContext, id) }, 250)
        } else {
            launch(ctx.applicationContext, id)
        }
    }

    private fun launch(ctx: Context, displayId: Int) {
        try {
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            ctx.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't move Moonfin-Auto to display $displayId", e)
        }
    }

    // ---- MainActivity reports where it is ---------------------------------

    fun onMainActivityDisplay(activity: Activity, displayId: Int) {
        mainActivity = WeakReference(activity)
        mainActivityDisplayId = displayId
        onStateChanged?.invoke()
    }

    fun onMainActivityGone(activity: Activity) {
        if (mainActivity.get() === activity) {
            mainActivity = WeakReference(null)
            mainActivityDisplayId = Display.DEFAULT_DISPLAY
            onStateChanged?.invoke()
        }
    }

    // ---- Input from the car screen ----------------------------------------

    fun dispatchTouch(event: MotionEvent): Boolean {
        if (driving || !isAppOnCar) return false
        val activity = mainActivity.get() ?: return false
        val copy = MotionEvent.obtain(event)
        return try {
            activity.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    fun dispatchBack(): Boolean {
        if (driving || !isAppOnCar) return false
        val activity = mainActivity.get() ?: return false
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        return true
    }

    // ---- First-time setup on the phone ------------------------------------

    fun markSetupNeeded(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SETUP_NEEDED, true).apply()
    }

    /**
     * Called from MainActivity.onResume on the phone. Asks for the permissions
     * the car screen needs, one per visit, until both are granted.
     */
    fun continueSetup(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SETUP_NEEDED, false)) return
        if (hasPermissions(activity)) {
            prefs.edit().putBoolean(KEY_SETUP_NEEDED, false).apply()
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                0x0CA2,
            )
        } else if (!Settings.canDrawOverlays(activity)) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${activity.packageName}"),
                ),
            )
        }
    }

    // ---- Parked / driving ---------------------------------------------------

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasSpeed()) return
            val speed = location.speed
            if (speed >= DRIVING_SPEED_MPS) {
                pendingParked?.let { handler.removeCallbacks(it) }
                pendingParked = null
                setDriving(true)
            } else if (driving && speed <= PARKED_SPEED_MPS && pendingParked == null) {
                val r = Runnable {
                    pendingParked = null
                    setDriving(false)
                }
                pendingParked = r
                handler.postDelayed(r, PARKED_DELAY_MS)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startDrivingMonitor(ctx: Context) {
        if (locationManager != null) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper(),
            )
            locationManager = lm
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't watch the car's speed", e)
        }
    }

    private fun stopDrivingMonitor() {
        locationManager?.removeUpdates(locationListener)
        locationManager = null
        pendingParked?.let { handler.removeCallbacks(it) }
        pendingParked = null
    }

    private fun setDriving(value: Boolean) {
        if (driving == value) return
        driving = value
        val engine = FlutterEngineCache.getInstance().get(AudioServicePlugin.getFlutterEngineId())
        engine?.let {
            MethodChannel(it.dartExecutor.binaryMessenger, CAR_CHANNEL)
                .invokeMethod("drivingChanged", value)
        }
        onStateChanged?.invoke()
    }
}
