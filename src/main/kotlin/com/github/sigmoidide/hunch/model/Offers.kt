package com.github.sigmoidide.hunch.model

import java.util.Locale
import kotlin.math.roundToInt

/** A GPU Vast.ai rents out now, as `ferry gpus --json` lists it, cheapest first. */
data class Offer(
    val id: Long,
    val gpu: String,
    val count: Int,
    /** GB per GPU. */
    val memory: Double,
    /** Dollars per hour, its disk included. */
    val price: Double,
    val reliability: Double,
    val tier: String,
    val place: String,
    /** Mb/s. */
    val download: Double,
    /** Dollars per GB sent to it. */
    val upCost: Double,
) {
    val label: String get() = if (count > 1) "${count}x $gpu" else gpu
}

/** What asking Ferry for the GPUs it can rent came to; [Found.understood] is how Ferry read the search words. */
sealed interface OffersAnswer {
    data class Found(val offers: List<Offer>, val understood: String = "any GPU") : OffersAnswer

    /** Older than Ferry 0.2, which rents. */
    object OldFerry : OffersAnswer

    data class Failed(val message: String) : OffersAnswer

    companion object {
        private val OLD = Regex("""invalid choice: '(gpus|login)'""")

        /** Search words for `ferry gpus`, shown in the search field. */
        const val SEARCH_HINT = "Search: 4090, a100, europe, us, <0.5 (per hour), 24gb, 2x, datacenter"

        /** Reads the outcome of `ferry gpus --json [words]`. */
        fun of(ok: Boolean, stdout: String, stderr: String): OffersAnswer {
            if (!ok) {
                if (OLD.containsMatchIn(stderr)) return OldFerry
                val last = stderr.trim().lines().lastOrNull()?.removePrefix("ferry: ")
                return Failed(if (last.isNullOrBlank()) "Ferry did not answer." else last)
            }
            val answer = (try { Json.parse(stdout) } catch (_: IllegalArgumentException) { null }) as? Map<*, *>
            val list = answer?.get("offers") as? List<*> ?: return Failed("Ferry's list of GPUs could not be read.")
            val understood = answer["understood"] as? String ?: "any GPU"
            return Found(understood = understood, offers = list.mapNotNull { item ->
                val o = item as? Map<*, *> ?: return@mapNotNull null
                val id = (o["id"] as? Number)?.toLong()
                val price = (o["price"] as? Number)?.toDouble()
                val gpu = o["gpu"] as? String
                if (id == null || price == null || gpu == null) return@mapNotNull null
                Offer(
                    id = id, gpu = gpu, price = price,
                    count = (o["count"] as? Number)?.toInt() ?: 1,
                    memory = (o["memory"] as? Number)?.toDouble() ?: 0.0,
                    reliability = (o["reliability"] as? Number)?.toDouble() ?: 0.0,
                    tier = o["tier"] as? String ?: "?",
                    place = o["place"] as? String ?: "?",
                    download = (o["download"] as? Number)?.toDouble() ?: 0.0,
                    upCost = (o["up_cost"] as? Number)?.toDouble() ?: 0.0,
                )
            })
        }
    }
}

/** How long a rented machine may run, and what that can cost, read as Ferry reads them. */
object Rental {
    /** The shortest time Ferry rents a machine for: it can take five minutes to start. */
    const val MIN_SECONDS = 600
    private val DURATION = Regex("""(?:(\d+(?:\.\d+)?)h)?(?:(\d+)m)?""")

    /** Seconds in 2h, 90m, 1h30m or 1.5h; null when Ferry would refuse it as --for. */
    fun seconds(text: String): Int? {
        val match = DURATION.matchEntire(text.trim().lowercase()) ?: return null
        val (hours, minutes) = match.destructured
        if (hours.isEmpty() && minutes.isEmpty()) return null
        val seconds = ((hours.toDoubleOrNull() ?: 0.0) * 3600 + (minutes.toIntOrNull() ?: 0) * 60).roundToInt()
        return seconds.takeIf { it >= MIN_SECONDS }
    }

    /** The most renting the offer for that long can cost, bandwidth aside: Ferry falls back only to offers at its price or less. */
    fun most(offer: Offer, seconds: Int): Double = offer.price * seconds / 3600

    /** The times to pick from for how long a rented machine may run, as Ferry's --for reads them. */
    val TIMES = listOf(
        "30m" to "30 minutes", "1h" to "1 hour", "2h" to "2 hours", "4h" to "4 hours", "8h" to "8 hours",
        "12h" to "12 hours", "24h" to "24 hours",
    )

    /** "at most $0.20, plus $0.0065 per GB you send it", or "..., data free". */
    fun cost(offer: Offer, seconds: Int): String {
        val data = if (offer.upCost > 0) "plus \$${"%.4f".format(Locale.ROOT, offer.upCost)} per GB you send it" else "data free"
        return "at most ${dollars(most(offer, seconds))}, $data"
    }

    /** "$0.13", or "under a cent", as Ferry says it. */
    fun dollars(amount: Double): String = if (amount < 0.01) "under a cent" else "\$" + "%.2f".format(Locale.ROOT, amount)

    fun number(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()
}
