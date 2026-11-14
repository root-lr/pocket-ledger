package io.pocketledger.shared

import java.text.Normalizer

object Text {
    /** Minuscules, sans accents, espaces normalisés : base des empreintes et des index aveugles. */
    fun normalize(value: String): String =
        Normalizer
            .normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
}
