package com.example.services

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class GeoPoint(
    val lat: Double,
    val lng: Double,
    val hasGpsFix: Boolean = false
)

class LocationTracker(context: Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    fun observeLocationUpdates(intervalMs: Long = 1500L): Flow<GeoPoint> = callbackFlow {
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                trySend(GeoPoint(loc.latitude, loc.longitude, hasGpsFix = true))
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(1.0f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                trySend(GeoPoint(loc.latitude, loc.longitude, hasGpsFix = true))
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            close(e)
        }

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }
}
