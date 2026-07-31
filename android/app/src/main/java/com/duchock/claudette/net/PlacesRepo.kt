package com.duchock.claudette.net

import android.content.Context
import android.util.Log
import com.duchock.claudette.location.LocationProvider
import com.duchock.claudette.util.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Google Places API (New) Text Search, biased to Nova's current location, for "what's around me"
 * questions (D13). Requires a Google Maps API key with the Places API (New) enabled
 * (Secrets.googleMapsKey). Returns a compact JSON list of nearby places with distances. On-demand;
 * nothing persisted.
 */
object PlacesRepo {
    private var appContext: Context? = null
    private var http: OkHttpClient? = null

    fun init(context: Context, client: OkHttpClient) {
        appContext = context.applicationContext
        http = client
    }

    /** Returns a compact JSON string: {origin, query, results:[...]} or {error:...}. */
    suspend fun search(query: String, radiusM: Int = 3000, limit: Int = 6): String = withContext(Dispatchers.IO) {
        val c = appContext ?: return@withContext err("not initialized")
        val client = http ?: return@withContext err("not initialized")
        val key = Secrets.googleMapsKey(c)
        if (key.isBlank()) return@withContext err("no Google Maps API key set (add it in Settings)")
        val fix = LocationProvider.current() ?: return@withContext err("location unavailable (permission or GPS)")

        val q = query.ifBlank { "points of interest" }
        val body = JSONObject()
            .put("textQuery", q)
            .put("maxResultCount", limit)
            .put(
                "locationBias", JSONObject().put(
                    "circle", JSONObject()
                        .put("center", JSONObject().put("latitude", fix.lat).put("longitude", fix.lng))
                        .put("radius", radiusM.toDouble())
                )
            )
        val req = Request.Builder()
            .url("https://places.googleapis.com/v1/places:searchText")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Goog-Api-Key", key)
            .addHeader(
                "X-Goog-FieldMask",
                "places.displayName,places.formattedAddress,places.location,places.primaryTypeDisplayName,places.rating,places.currentOpeningHours.openNow"
            )
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val s = resp.body?.string()
                if (!resp.isSuccessful || s == null) {
                    Log.e(TAG, "Places HTTP ${resp.code}: ${s?.take(200)}")
                    return@withContext err("places lookup failed (HTTP ${resp.code})")
                }
                val places = JSONObject(s).optJSONArray("places") ?: JSONArray()
                val rows = ArrayList<JSONObject>()
                for (i in 0 until places.length()) {
                    val p = places.getJSONObject(i)
                    val loc = p.optJSONObject("location")
                    val dist = if (loc != null)
                        haversine(fix.lat, fix.lng, loc.optDouble("latitude"), loc.optDouble("longitude")) else -1.0
                    rows.add(
                        JSONObject()
                            .put("name", p.optJSONObject("displayName")?.optString("text") ?: "")
                            .put("address", p.optString("formattedAddress"))
                            .put("type", p.optJSONObject("primaryTypeDisplayName")?.optString("text") ?: "")
                            .put("rating", p.opt("rating") ?: JSONObject.NULL)
                            .put("openNow", p.optJSONObject("currentOpeningHours")?.opt("openNow") ?: JSONObject.NULL)
                            .put("distanceMeters", if (dist >= 0) dist.roundToInt() else JSONObject.NULL)
                    )
                }
                rows.sortBy { it.optInt("distanceMeters", Int.MAX_VALUE) }
                val results = JSONArray()
                rows.forEach { results.put(it) }
                JSONObject()
                    .put("origin", JSONObject().put("city", fix.city ?: JSONObject.NULL).put("address", fix.address ?: JSONObject.NULL))
                    .put("query", q)
                    .put("results", results).toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "places call failed", e); err("places lookup error: ${e.message}")
        }
    }

    private fun err(m: String) = JSONObject().put("error", m).toString()

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private const val TAG = "PlacesRepo"
}
