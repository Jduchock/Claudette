package com.duchock.claudette.demo

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic tool definitions for the inventory demo, plus the executor that runs each call
 * against InventoryRepo (deterministic). Nova calls these while demo mode is active; the
 * results are compact JSON she narrates in her own voice.
 *
 * check_stock returns a pre-computed "summary" (home in-stock sizes/units, nearby
 * availability, anyInStock) so Nova reads the answer directly instead of reasoning across
 * raw rows -- this is what stops false "out of stock" calls.
 */
object InventoryTools {
    private const val TAG = "NovaTools"

    fun schema(): JSONArray {
        val tools = JSONArray()

        tools.put(
            JSONObject()
                .put("name", "check_stock")
                .put(
                    "description",
                    "Look up a shoe's availability. You can search by a loose description (brand, model, " +
                        "and/or colorway -- word order and partial colors are fine, e.g. 'Kobe 5 in white and purple'), " +
                        "and/or by an exact identifier (SKU like HB-4101174, a UPC, or an Item ID like ITM-700156) " +
                        "passed in 'identifier'. Optionally narrow by size. Returns a 'summary' (in-stock sizes and " +
                        "unit counts at the home store 1511 and at each nearby store, plus anyInStock) and per-size " +
                        "'items' with back-room shelf locations at 1511. A count of null means the store does not carry " +
                        "that item; 0 means carried but out of stock. Use digits for sizes (10.5, not 'ten and a half')."
                )
                .put(
                    "input_schema", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("model", JSONObject().put("type", "string").put("description", "Description text: brand, model, and/or colorway. e.g. 'Nike Kobe 5 Protro white purple'"))
                                .put("identifier", JSONObject().put("type", "string").put("description", "Exact SKU, UPC, or Item ID if the associate gives one, e.g. 'HB-4101174'. Optional."))
                                .put("size", JSONObject().put("type", "string").put("description", "US size as digits, e.g. '10.5', '11', '5Y'. Optional."))
                                .put("color", JSONObject().put("type", "string").put("description", "Colorway keyword(s). Optional -- can also be folded into model."))
                        )
                        .put("required", JSONArray())
                )
        )

        tools.put(
            JSONObject()
                .put("name", "store_rollup")
                .put(
                    "description",
                    "Total units on hand across the six stores, optionally filtered by brand, category, or gender. " +
                        "Returns per-store totals (1511 home plus five others), the chain total, and how many styles match."
                )
                .put(
                    "input_schema", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("brand", JSONObject().put("type", "string").put("description", "Brand filter. Optional."))
                                .put("category", JSONObject().put("type", "string").put("description", "Category or sub-category keyword such as Running, Basketball, Sandals. Optional."))
                                .put("gender", JSONObject().put("type", "string").put("description", "Men, Women, Kids, or Unisex. Optional."))
                        )
                        .put("required", JSONArray())
                )
        )

        tools.put(
            JSONObject()
                .put("name", "pricing_lookup")
                .put(
                    "description",
                    "Price and lifecycle (Active / Clearance / Pre-Season) for a shoe by model/description, or a list " +
                        "of current clearance styles when clearance_only is true. Returns MSRP and current retail."
                )
                .put(
                    "input_schema", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("model", JSONObject().put("type", "string").put("description", "Shoe name/model/description. Optional."))
                                .put("clearance_only", JSONObject().put("type", "boolean").put("description", "If true, list current clearance styles."))
                        )
                        .put("required", JSONArray())
                )
        )

        return tools
    }

    fun execute(name: String, input: JSONObject): String {
        Log.i(TAG, "execute name=$name loaded=${InventoryRepo.isLoaded()} input=${input.toString().take(200)}")
        if (!InventoryRepo.isLoaded()) {
            return JSONObject().put("error", "inventory not loaded yet, ask again in a moment").toString()
        }
        val out = when (name) {
            "check_stock" -> checkStock(input)
            "store_rollup" -> storeRollup(input)
            "pricing_lookup" -> pricing(input)
            else -> JSONObject().put("error", "unknown tool $name").toString()
        }
        Log.i(TAG, "execute name=$name -> ${out.length} chars")
        return out
    }

    private fun checkStock(input: JSONObject): String {
        val model = input.optString("model").ifBlank { null }
        val identifier = input.optString("identifier").ifBlank { null }
        val size = input.optString("size").ifBlank { null }
        val color = input.optString("color").ifBlank { null }
        val rows = InventoryRepo.search(model, size, color, identifier)

        val home = InventoryRepo.HOME
        val nearbyIds = InventoryRepo.STORE_IDS.filter { it != home }

        // ---- pre-computed, unambiguous summary ----
        val homeSizes = JSONArray()
        var homeUnits = 0
        for (it in rows) {
            val q = it.onHand[home] ?: 0
            if (q > 0) { homeSizes.put(JSONObject().put("size", it.size).put("units", q)); homeUnits += q }
        }
        val nearby = JSONArray()
        for (sid in nearbyIds) {
            val sizesHere = JSONArray()
            var units = 0
            for (it in rows) {
                val q = it.onHand[sid] ?: 0
                if (q > 0) { sizesHere.put(JSONObject().put("size", it.size).put("units", q)); units += q }
            }
            if (units > 0) {
                nearby.put(
                    JSONObject().put("store", sid).put("name", InventoryRepo.store(sid)?.name ?: sid)
                        .put("units", units).put("sizes", sizesHere)
                )
            }
        }
        val anyInStock = homeUnits > 0 || nearby.length() > 0
        val summary = JSONObject()
            .put("matchCount", rows.size)
            .put("anyInStock", anyInStock)
            .put("homeStore", home)
            .put("homeInStockSizes", homeSizes)
            .put("homeUnits", homeUnits)
            .put("nearbyAvailability", nearby)

        // ---- per-size detail rows ----
        val arr = JSONArray()
        for (it in rows.take(40)) {
            val o = JSONObject()
                .put("sku", it.sku).put("brand", it.brand).put("model", it.model)
                .put("color", it.color).put("size", it.size).put("width", it.width)
            val oh = JSONObject()
            for ((k, v) in it.onHand) oh.put(k, v ?: JSONObject.NULL)
            o.put("onHand", oh)
            if (it.loc != null) {
                o.put(
                    "homeLocation", JSONObject()
                        .put("locId", it.loc.locId).put("shelf", it.loc.shelfLabel)
                        .put("reach", it.loc.reach).put("backRoom", it.loc.backRoom)
                        .put("salesFloor", it.loc.salesFloor).put("status", it.loc.status)
                )
            }
            arr.put(o)
        }

        return JSONObject().put("summary", summary).put("items", arr)
            .put("storeNames", storeNames()).toString()
    }

    private fun storeRollup(input: JSONObject): String {
        val r = InventoryRepo.rollup(
            input.optString("brand").ifBlank { null },
            input.optString("category").ifBlank { null },
            input.optString("gender").ifBlank { null }
        )
        val per = JSONObject()
        for ((k, v) in r.perStore) per.put(k, v)
        return JSONObject()
            .put("perStore", per).put("chainTotal", r.total)
            .put("styles", r.styles).put("sizeSkus", r.sizeSkus)
            .put("storeNames", storeNames()).toString()
    }

    private fun pricing(input: JSONObject): String {
        val rows = InventoryRepo.pricing(
            input.optString("model").ifBlank { null },
            input.optBoolean("clearance_only", false)
        )
        val arr = JSONArray()
        for (p in rows) arr.put(
            JSONObject()
                .put("brand", p.brand).put("model", p.model).put("color", p.color)
                .put("msrp", p.msrp).put("retail", p.retail).put("lifecycle", p.life)
        )
        return JSONObject().put("count", rows.size).put("styles", arr).toString()
    }

    private fun storeNames(): JSONObject {
        val o = JSONObject()
        for (s in InventoryRepo.storeList) {
            o.put(s.number, "${s.name} (${s.city}, ${s.state}" + (if (s.home) ", HOME" else ", nearby") + ")")
        }
        return o
    }
}
