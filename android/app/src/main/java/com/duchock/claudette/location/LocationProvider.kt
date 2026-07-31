package com.duchock.claudette.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * On-demand location (D13). Fetches a FRESH fix only when a question needs it -- no continuous
 * tracking, which is best for battery and privacy (ref threat S10). The fix is reverse-geocoded
 * to a human place name and used only for the immediate query; nothing is persisted.
 */
object LocationProvider {

    data class Fix(
        val lat: Double, val lng: Double, val accuracyM: Float,
        val address: String?, val city: String?, val area: String?, val ageMs: Long
    )

    private var appContext: Context? = null
    private var client: FusedLocationProviderClient? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        client = LocationServices.getFusedLocationProviderClient(appContext!!)
    }

    fun hasPermission(): Boolean {
        val c = appContext ?: return false
        val fine = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** Current location, or null if unavailable (no permission / no provider / timeout). */
    suspend fun current(): Fix? {
        val c = appContext ?: return null
        val cl = client ?: return null
        if (!hasPermission()) return null
        val loc = try {
            getFix(cl)
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission denied at call time", e); null
        } catch (e: Exception) {
            Log.e(TAG, "location fetch failed", e); null
        } ?: return null
        val (addr, city, area) = reverseGeocode(c, loc.latitude, loc.longitude)
        return Fix(loc.latitude, loc.longitude, loc.accuracy, addr, city, area,
            System.currentTimeMillis() - loc.time)
    }

    @Suppress("MissingPermission")
    private suspend fun getFix(cl: FusedLocationProviderClient): Location? =
        suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            cl.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
            cont.invokeOnCancellation { cts.cancel() }
        }

    private suspend fun reverseGeocode(c: Context, lat: Double, lng: Double): Triple<String?, String?, String?> =
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(c, Locale.getDefault()).getFromLocation(lat, lng, 1)
                val a = list?.firstOrNull() ?: return@withContext Triple(null, null, null)
                val line = a.getAddressLine(0)
                val city = a.locality ?: a.subAdminArea
                val area = listOfNotNull(city, a.adminArea).joinToString(", ").ifBlank { null }
                Triple(line, city, area)
            } catch (e: Exception) {
                Log.w(TAG, "reverse geocode failed", e); Triple(null, null, null)
            }
        }

    private const val TAG = "LocationProvider"
}
