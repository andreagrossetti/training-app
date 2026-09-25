package com.andreagrossetti.training.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.andreagrossetti.training.data.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One-shot position + address lookup, to tag a diary entry with where it was done. */
object PlaceLocator {
    fun hasPermission(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun isEnabled(context: Context): Boolean =
        LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))

    /** The current place, or null if no fix arrived in time. Needs a location permission. */
    suspend fun locate(context: Context): Place? {
        val location = currentLocation(context) ?: return null
        return Place(location.latitude, location.longitude, address(context, location))
    }

    @SuppressLint("MissingPermission") // callers check hasPermission()
    private suspend fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in manager.allProviders -> LocationManager.FUSED_PROVIDER
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        // A fix from the last couple of minutes is as good as a new one, and instant.
        val recent = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time < RECENT_MS }
        if (recent != null) return recent
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) {
                    if (cont.isActive) cont.resume(it)
                }
            }
        }
    }

    /** "Via Roma 1, Milano", or null when there is no geocoder or no connection. */
    private suspend fun address(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.ITALIAN)
        val address: Address? = withTimeoutOrNull(TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
        return address?.let { a ->
            val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null }
            listOfNotNull(street ?: a.featureName, a.locality).distinct().joinToString(", ").ifBlank { null }
        }
    }

    private const val RECENT_MS = 2 * 60_000L
    private const val TIMEOUT_MS = 30_000L
}
