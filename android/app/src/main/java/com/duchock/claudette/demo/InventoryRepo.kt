package com.duchock.claudette.demo

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads the MFCS footwear demo dataset (assets/inventory_demo.json) and answers inventory
 * questions DETERMINISTICALLY in Kotlin -- Nova's counts and shelf locations come from real
 * filtering here, never from the model's own arithmetic. Backs the demo tools (InventoryTools).
 *
 * Lookup is deliberately forgiving:
 *  - Exact SKU / UPC / Item ID (MFCS SKUs are 8-digit numbers).
 *  - Numeric SKUs are also matched even when speech-to-text splits or groups the digits
 *    ("38 464 624"), spells them out ("three eight four six ..."), or appends a size.
 *  - Otherwise, word-order-independent, stopword-stripped token matching across
 *    brand + model + description + color, so loose phrasings still land.
 *
 * Home store is 1511 (Homewood/Wildwood, Hibbett); 33/54/513/966 are nearby Birmingham stores; 107 (Sebring FL) is a distant outlier store.
 */
object InventoryRepo {

    const val HOME = "1511"
    val STORE_IDS = listOf("1511", "33", "54", "513", "966", "107")

    data class Store(
        val number: String, val name: String, val city: String, val state: String,
        val banner: String, val depth: String, val home: Boolean
    )

    data class Loc(
        val locId: String, val shelfLabel: String, val reach: String,
        val backRoom: Int, val salesFloor: Int, val status: String, val say: String
    )

    data class Item(
        val sku: String, val upc: String, val itemId: String,
        val brand: String, val model: String, val style: String,
        val desc: String, val cat: String, val sub: String, val gender: String,
        val color: String, val size: String, val width: String, val season: String,
        val life: String, val msrp: Double, val retail: Double,
        val onHand: Map<String, Int?>, val loc: Loc?
    )

    @Volatile private var loaded = false
    private val stores = ArrayList<Store>()
    private val items = ArrayList<Item>()

    val storeList: List<Store> get() = stores
    fun store(num: String): Store? = stores.firstOrNull { it.number == num }
    fun isLoaded() = loaded

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            val text = context.assets.open("inventory_demo.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val sArr = root.getJSONArray("stores")
            for (i in 0 until sArr.length()) {
                val o = sArr.getJSONObject(i)
                stores.add(
                    Store(
                        o.getString("number"), o.optString("name"), o.optString("city"),
                        o.optString("state"), o.optString("banner"), o.optString("depth"),
                        o.optBoolean("home")
                    )
                )
            }
            val iArr = root.getJSONArray("items")
            for (i in 0 until iArr.length()) {
                val o = iArr.getJSONObject(i)
                val oh = o.getJSONObject("onHand")
                val ohMap = HashMap<String, Int?>()
                for (k in STORE_IDS) ohMap[k] = if (oh.isNull(k)) null else oh.getInt(k)
                var loc: Loc? = null
                if (o.has("loc")) {
                    val l = o.getJSONObject("loc")
                    loc = Loc(
                        l.optString("locId"), l.optString("shelfLabel"), l.optString("reach"),
                        l.optInt("backRoom"), l.optInt("salesFloor"), l.optString("status"),
                        l.optString("say")
                    )
                }
                items.add(
                    Item(
                        o.optString("sku"), o.optString("upc"), o.optString("itemId"),
                        o.optString("brand"), o.optString("model"), o.optString("style"),
                        o.optString("desc"), o.optString("cat"), o.optString("sub"),
                        o.optString("gender"), o.optString("color"), o.optString("size"),
                        o.optString("width"), o.optString("season"), o.optString("life"),
                        o.optDouble("msrp", 0.0), o.optDouble("retail", 0.0), ohMap, loc
                    )
                )
            }
            loaded = true
            Log.i(TAG, "Inventory loaded: ${items.size} items, ${stores.size} stores")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load inventory_demo.json", e)
        }
    }

    // ---- normalization ----
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "in", "of", "with", "for", "to", "please",
        "pair", "pairs", "shoe", "shoes", "sneaker", "sneakers", "size", "sz",
        "color", "colour", "colored", "coloured", "do", "we", "you", "have", "has",
        "any", "some", "got", "get", "me", "i", "is", "it", "that", "this", "one",
        "them", "there", "looking", "look", "find", "need", "want", "check", "stock",
        "men", "mens", "man", "women", "womens", "woman", "kids", "kid", "youth"
    )

    private fun norm(s: String): String =
        s.lowercase()
            .replace("grey", "gray")
            .replace("colour", "color")
            .replace(Regex("[^a-z0-9. ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** ID form: strip everything but letters+digits, lowercase. "HB-4101174" -> "hb4101174". */
    private fun normId(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Digits only -- for numeric SKU / Item ID matching. */
    private fun digitsOnly(s: String?): String = (s ?: "").replace(Regex("[^0-9]"), "")

    private val NUMWORD = mapOf(
        "zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
    )

    /**
     * Turn a spoken/typed string into a digit string, so a SKU survives however STT rendered it:
     * kept if already digits ("38464624"), joined if grouped ("38 464 624"), and converted if
     * spelled out ("three eight four six four six two four"). Non-number words are dropped.
     */
    private fun spokenToDigits(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val sb = StringBuilder()
        for (tok in s.lowercase().split(Regex("[^a-z0-9]+"))) {
            when {
                tok.isBlank() -> {}
                tok.all { it.isDigit() } -> sb.append(tok)
                NUMWORD.containsKey(tok) -> sb.append(NUMWORD[tok])
            }
        }
        return sb.toString()
    }

    private fun sizeNorm(s: String) = s.lowercase().trim().removeSuffix(".0")

    private fun tokens(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return norm(s).split(" ").filter { it.isNotBlank() && it !in STOP }
    }

    private fun haystack(it: Item): String =
        norm("${it.brand} ${it.model} ${it.desc} ${it.sub} ${it.cat} ${it.color} ${it.gender}")

    /** A token looks like an identifier if it resembles a SKU/UPC/Item ID. */
    private fun looksLikeId(raw: String): Boolean {
        val n = normId(raw)
        if (n.length < 5) return false
        val digits = n.count { it.isDigit() }
        // letter+digit codes (e.g. HB4101174), or a run of >=6 digits (MFCS numeric item numbers)
        return (n.any { it.isLetter() } && digits >= 3) || digits >= 6
    }

    /** Exact whole-token match on SKU / UPC / Item ID / style (parent) number. */
    private fun idHits(candidates: List<String>): List<Item> {
        val wanted = candidates.map { normId(it) }.filter { it.length >= 5 }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return items.filter {
            normId(it.sku) in wanted || normId(it.upc) in wanted ||
                normId(it.itemId) in wanted || normId(it.style) in wanted
        }
    }

    /**
     * Numeric fallback: find any item whose SKU / Item ID digits appear as a run inside [qd]
     * (the digit string distilled from the spoken query). Survives STT grouping / spelled-out
     * digits and a trailing size, e.g. qd "2759114885" still contains SKU "27591148".
     */
    private fun idHitsByDigits(qd: String): List<Item> {
        if (qd.length < 6) return emptyList()
        return items.filter {
            val s = digitsOnly(it.sku)
            val id = digitsOnly(it.itemId)
            (s.length >= 6 && qd.contains(s)) || (id.length >= 6 && qd.contains(id))
        }
    }

    /**
     * Flexible stock search. Tries an exact identifier match first (SKU/UPC/Item ID given in
     * [identifier] or embedded in [query]); then a numeric-digit fallback for spoken SKUs;
     * otherwise falls back to token matching across brand/model/description/color, with [color]
     * folded into the tokens so partial colorways work regardless of word order. [size] applies on top.
     */
    fun search(query: String?, size: String?, color: String?, identifier: String?, limit: Int = 60): List<Item> {
        // 1) identifier path -- exact whole-token match
        val idCands = ArrayList<String>()
        if (!identifier.isNullOrBlank()) idCands.add(identifier)
        query?.split(Regex("\\s+"))?.forEach { if (looksLikeId(it)) idCands.add(it) }
        var hits = idHits(idCands)
        // 1b) numeric fallback for spoken / grouped / spelled-out SKUs
        if (hits.isEmpty()) {
            val qd = spokenToDigits((identifier ?: "") + " " + (query ?: ""))
            hits = idHitsByDigits(qd)
        }
        if (hits.isNotEmpty()) {
            // if a size was also given, prefer that size; but a SKU already pins the exact size,
            // so never return empty just because a spoken size didn't line up.
            val bySize = hits.filter { size.isNullOrBlank() || sizeNorm(it.size) == sizeNorm(size) }
            return (if (bySize.isNotEmpty()) bySize else hits).take(limit)
        }
        // 2) token path (query + color folded together)
        val toks = (tokens(query) + tokens(color)).distinct()
        if (toks.isEmpty()) return emptyList()
        val res = items.filter { it ->
            val hay = haystack(it)
            toks.all { hay.contains(it) } &&
                (size.isNullOrBlank() || sizeNorm(it.size) == sizeNorm(size))
        }
        return res.take(limit)
    }

    private fun matches(it: Item, query: String?): Boolean {
        val toks = tokens(query)
        if (toks.isEmpty()) return true
        val hay = haystack(it)
        return toks.all { hay.contains(it) }
    }

    data class Rollup(val perStore: Map<String, Int>, val total: Int, val styles: Int, val sizeSkus: Int)

    fun rollup(brand: String?, category: String?, gender: String?): Rollup {
        val sel = items.filter {
            (brand.isNullOrBlank() || norm(it.brand).contains(norm(brand))) &&
                (category.isNullOrBlank() || norm(it.cat).contains(norm(category)) || norm(it.sub).contains(norm(category))) &&
                (gender.isNullOrBlank() || norm(it.gender).contains(norm(gender)))
        }
        val per = LinkedHashMap<String, Int>()
        for (s in STORE_IDS) per[s] = sel.sumOf { (it.onHand[s] ?: 0) }
        return Rollup(per, per.values.sum(), sel.map { it.style }.toSet().size, sel.size)
    }

    data class PriceRow(
        val brand: String, val model: String, val color: String,
        val msrp: Double, val retail: Double, val life: String
    )

    fun pricing(model: String?, clearanceOnly: Boolean, limit: Int = 25): List<PriceRow> {
        val seen = HashSet<String>()
        val out = ArrayList<PriceRow>()
        for (it in items) {
            if (!model.isNullOrBlank() && !matches(it, model)) continue
            if (clearanceOnly && !it.life.equals("Clearance", true)) continue
            if (!seen.add(it.style + "|" + it.color)) continue
            out.add(PriceRow(it.brand, it.model, it.color, it.msrp, it.retail, it.life))
            if (out.size >= limit) break
        }
        return out
    }

    private const val TAG = "InventoryRepo"
}
