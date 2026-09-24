package com.xiangqi.assist.assist

/** A one-shot request to choose a non-default move for the current position. */
object NextVariationPolicy {
    data class Request(
        val position: String,
        /** Default move captured when the user requested a variation. */
        val defaultUcci: String?,
        /** Requested alternative UCCI. Keep it stable across candidate-list reorder/removal. */
        val preferredUcci: String? = null,
    )

    data class Resolution(
        val request: Request?,
        val ucci: String?,
        val candidateIndex: Int?,
        val waitingForAlternative: Boolean,
        val invalidated: Boolean = false,
    )

    /** Toggle the stored next-move intent; a second tap cancels it. */
    fun toggleArmed(current: Boolean): Boolean = !current

    /** Register or advance a request. The service may create it before candidates exist. */
    fun request(
        position: String,
        defaultUcci: String?,
        candidates: List<String>,
        current: Request?,
    ): Request {
        val same = current?.takeIf { it.position == position }
        val baseline = same?.defaultUcci ?: defaultUcci ?: candidates.firstOrNull()
        val old = same?.let { resolve(it, position, candidates) }
        val alternatives = alternatives(candidates, baseline)
        val oldMove = same?.preferredUcci
        if (oldMove != null && oldMove !in alternatives) {
            return same.copy(defaultUcci = baseline)
        }
        if (alternatives.isEmpty()) return Request(position, baseline, null)

        val selectedNow = oldMove ?: old?.ucci
        val next = when {
            selectedNow == null -> alternatives.first()
            alternatives.size == 1 -> selectedNow
            else -> alternatives[(alternatives.indexOf(selectedNow).coerceAtLeast(0) + 1) % alternatives.size]
        }
        return Request(position, baseline, next)
    }

    /** Resolve against current candidates without losing a temporarily absent preferred move. */
    fun resolve(
        request: Request?,
        position: String,
        candidates: List<String>,
        currentDefaultUcci: String? = null,
    ): Resolution {
        if (request == null) return Resolution(null, null, null, waitingForAlternative = false)
        if (request.position != position) {
            return Resolution(null, null, null, waitingForAlternative = false, invalidated = true)
        }
        val baseline = request.defaultUcci ?: currentDefaultUcci ?: candidates.firstOrNull()
        val normalized = request.copy(defaultUcci = baseline)
        val available = alternatives(candidates, baseline)
        val preferred = request.preferredUcci
        if (preferred == null) {
            if (available.isEmpty()) return Resolution(normalized, null, null, true)
            val chosen = available.first()
            return Resolution(
                normalized.copy(preferredUcci = chosen),
                chosen,
                candidates.indexOf(chosen).takeIf { it >= 0 },
                waitingForAlternative = false,
            )
        }
        if (preferred !in available) return Resolution(normalized, null, null, true)
        return Resolution(
            normalized,
            preferred,
            candidates.indexOf(preferred).takeIf { it >= 0 },
            waitingForAlternative = false,
        )
    }

    /** Consume only after the actual requested non-default move has been dispatched. */
    fun consume(request: Request?, position: String, executedUcci: String): Request? {
        if (request == null || request.position != position) return request
        val preferred = request.preferredUcci ?: return request
        val baseline = request.defaultUcci
        return if (executedUcci == preferred && executedUcci != baseline) null else request
    }

    private fun alternatives(candidates: List<String>, baseline: String?): List<String> =
        candidates.asSequence()
            .filter { it.isNotBlank() && it != baseline }
            .distinct()
            .toList()
}
