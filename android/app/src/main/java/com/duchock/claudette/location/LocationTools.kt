package com.duchock.claudette.location

import com.duchock.claudette.net.PlacesRepo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools Nova can call on the normal (non-demo) path: get_location (where she is right now) and
 * nearby_places (what's around her, via Google Places). Both are on-demand and privacy-preserving.
 */
object LocationTools {

    fun schema(): JSONArray {
        val tools = JSONArray()
        tools.put(
            JSONObject()
                .put("name", "get_location")
                .put(
                    "description",
                    "Get John's current location right now: coordinates plus a human-readable address, " +
                        "city, and area. Call this whenever the answer depends on where he is (\"where am I\", " +
                        "\"what city am I in\", near-me questions, or local weather/time context)."
                )
                .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()))
        )
        tools.put(
            JSONObject()
                .put("name", "nearby_places")
                .put(
                    "description",
                    "Find places near John's current location (restaurants, coffee, gas, pharmacy, stores, " +
                        "ATMs, etc.). Returns names, addresses, distance in meters, rating, and whether open now, " +
                        "nearest first. Use for \"what's around me\", \"nearest X\", \"where can I get Y\"."
                )
                .put(
                    "input_schema", JSONObject().put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("query", JSONObject().put("type", "string").put("description", "What to look for, e.g. 'coffee', 'gas station', 'pharmacy', 'Mexican restaurant'."))
                                .put("radius_meters", JSONObject().put("type", "integer").put("description", "Search radius in meters (default 3000)."))
                        )
                        .put("required", JSONArray().put("query"))
                )
        )
        return tools
    }

    suspend fun execute(name: String, input: JSONObject): String = when (name) {
        "get_location" -> getLocation()
        "nearby_places" -> PlacesRepo.search(input.optString("query"), input.optInt("radius_meters", 3000))
        else -> JSONObject().put("error", "unknown tool $name").toString()
    }

    private suspend fun getLocation(): String {
        if (!LocationProvider.hasPermission())
            return JSONObject().put("error", "location permission not granted yet — ask John to enable location for Nova").toString()
        val f = LocationProvider.current()
            ?: return JSONObject().put("error", "couldn't get a location fix right now").toString()
        return JSONObject()
            .put("lat", f.lat).put("lng", f.lng).put("accuracyMeters", f.accuracyM.toInt())
            .put("address", f.address ?: JSONObject.NULL)
            .put("city", f.city ?: JSONObject.NULL)
            .put("area", f.area ?: JSONObject.NULL)
            .toString()
    }
}
