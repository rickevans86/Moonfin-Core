package org.moonfin.androidtv.car

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.ryanheise.audioservice.AudioServicePlugin
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.lang.ref.WeakReference

/**
 * Shows Moonfin-Auto on the Android Auto display.
 *
 * Moonfin-Auto runs on one Flutter engine shared by everything in the app.
 * While the car screen is open, that engine draws into a FlutterView inside
 * [MoonfinCarActivity] instead of the phone's MainActivity, so the full app
 * (video player included) appears in the car and touches work directly.
 * Opening the app on the phone hands the engine back to the phone.
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
    private var mainActivity = WeakReference<Activity>(null)
    private var mainActivityResumed = false
    private var carView: FlutterView? = null
    private var engine: FlutterEngine? = null
    private var locationManager: LocationManager? = null
    private var pendingParked: Runnable? = null

    /** Called whenever what the car screen should show may have changed. */
    var onStateChanged: (() -> Unit)? = null

    /** True while the app is drawing on the car screen. */
    var isAppOnCar = false
        private set

    var driving = false
        private set

    /** Last reason showing the app on the car failed, for the car screen. */
    var lastError: String? = null
        private set

    fun hasPermissions(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---- Car session -------------------------------------------------------

    /** Moves the app's drawing into [host], a view on the car screen. */
    fun showOnCar(carContext: Context, host: ViewGroup) {
        if (isAppOnCar || driving) return
        try {
            val eng = AudioServicePlugin.getFlutterEngine(carContext.applicationContext)
            engine = eng
            findFlutterView(mainActivity.get())?.let {
                if (it.isAttachedToFlutterEngine) it.detachFromFlutterEngine()
            }
            // Native views inside Flutter (the video player) are placed using
            // the phone's screen density, since they belong to the phone's
            // activity. Give the car's Flutter view the same density so the
            // two agree (otherwise video comes out a fraction of its size),
            // and tell Dart the car's real density so it lays the app out for
            // the car (CarUiScale).
            val carDensity = carContext.resources.displayMetrics.density
            val phoneDensity = carContext.applicationContext.resources.displayMetrics.density
            val config = android.content.res.Configuration(carContext.resources.configuration)
            config.densityDpi = carContext.applicationContext.resources.configuration.densityDpi
            val viewContext = android.view.ContextThemeWrapper(carContext, 0).apply {
                applyOverrideConfiguration(config)
            }
            val view = FlutterView(viewContext)
            host.addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            view.attachToFlutterEngine(eng)
            carView = view
            isAppOnCar = true
            lastError = null
            // The phone is usually locked in the car. Flutter stops drawing
            // when the app is paused, so tell it it's in front.
            eng.lifecycleChannel.appIsResumed()
            sendUiScale(eng, carDensity / phoneDensity)
            startDrivingMonitor(carContext.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show Moonfin-Auto on the car screen", e)
            lastError = e.message ?: e.javaClass.simpleName
            removeCarView()
        }
        onStateChanged?.invoke()
    }

    /** Gives the app's drawing back to the phone. */
    fun returnToPhone() {
        if (!isAppOnCar) return
        removeCarView()
        val eng = engine
        if (eng != null) {
            sendUiScale(eng, null)
            findFlutterView(mainActivity.get())?.let {
                if (!it.isAttachedToFlutterEngine) it.attachToFlutterEngine(eng)
            }
            if (!mainActivityResumed) eng.lifecycleChannel.appIsPaused()
        }
        onStateChanged?.invoke()
    }

    /** Android Auto closed Moonfin-Auto Video or disconnected. */
    fun endSession() {
        stopDrivingMonitor()
        setDriving(false)
        // Video playing on the car screen stops with it; audio carries on.
        engine?.let {
            MethodChannel(it.dartExecutor.binaryMessenger, CAR_CHANNEL)
                .invokeMethod("carClosed", null)
        }
        returnToPhone()
    }

    private fun sendUiScale(eng: FlutterEngine, scale: Float?) {
        MethodChannel(eng.dartExecutor.binaryMessenger, CAR_CHANNEL)
            .invokeMethod("uiScale", scale?.toDouble())
    }

    private fun removeCarView() {
        carView?.let { view ->
            runCatching { if (view.isAttachedToFlutterEngine) view.detachFromFlutterEngine() }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        carView = null
        isAppOnCar = false
    }

    fun dispatchBack(): Boolean {
        if (driving || !isAppOnCar) return false
        engine?.navigationChannel?.popRoute() ?: return false
        return true
    }

    // ---- MainActivity reports in -------------------------------------------

    /** Whether MainActivity should pass its lifecycle on to Flutter. */
    val phoneOwnsLifecycle: Boolean
        get() = !isAppOnCar

    fun onMainActivityCreated(activity: Activity) {
        mainActivity = WeakReference(activity)
    }

    fun onMainActivityResumed(activity: Activity) {
        mainActivity = WeakReference(activity)
        mainActivityResumed = true
        // Opening the app on the phone takes it back from the car.
        if (isAppOnCar) returnToPhone()
        engine?.lifecycleChannel?.appIsResumed()
    }

    fun onMainActivityPaused(activity: Activity) {
        if (mainActivity.get() === activity) mainActivityResumed = false
    }

    fun onMainActivityGone(activity: Activity) {
        if (mainActivity.get() === activity) {
            mainActivity = WeakReference(null)
            mainActivityResumed = false
        }
    }

    private fun findFlutterView(activity: Activity?): FlutterView? {
        val root = activity?.window?.decorView ?: return null
        return findFlutterView(root)
    }

    private fun findFlutterView(view: View): FlutterView? {
        if (view is FlutterView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFlutterView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ---- First-time setup on the phone ------------------------------------

    fun markSetupNeeded(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SETUP_NEEDED, true).apply()
    }

    /** Called from MainActivity.onResume: asks for location if the car needs it. */
    fun continueSetup(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SETUP_NEEDED, false)) return
        if (hasPermissions(activity)) {
            prefs.edit().putBoolean(KEY_SETUP_NEEDED, false).apply()
            return
        }
        activity.requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            0x0CA2,
        )
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
        if (!hasPermissions(ctx)) return
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
        engine?.let {
            MethodChannel(it.dartExecutor.binaryMessenger, CAR_CHANNEL)
                .invokeMethod("drivingChanged", value)
        }
        onStateChanged?.invoke()
    }
}
