package org.moonfin.androidtv.car

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/** Android Auto binds this to show Moonfin-Auto's full screen in the car. */
class MoonfinCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = MoonfinCarActivity::class.java
}
