package cl.cornejotux.antplus

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.*

class LocationTracker(context: Context, private val onLocation: (Location) -> Unit) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
        .setMinUpdateDistanceMeters(5f).build()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach(onLocation) }
    }
    @SuppressLint("MissingPermission") fun start() { client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper()) }
    fun stop() { client.removeLocationUpdates(callback) }
}
