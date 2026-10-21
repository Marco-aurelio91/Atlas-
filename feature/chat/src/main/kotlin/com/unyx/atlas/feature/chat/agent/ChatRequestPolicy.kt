package com.unyx.atlas.feature.chat.agent

import java.text.Normalizer

/** Small, deterministic privacy and intent rules applied before a model sees personal context. */
internal object ChatRequestPolicy {
    fun isPublicFacing(text: String): Boolean {
        val value = normalize(text)
        return PUBLIC_MARKERS.any(value::contains)
    }

    fun isCorrectionForExternalAudience(text: String): Boolean {
        val value = normalize(text)
        return listOf("nao pra mim", "nao para mim", "para outras pessoas", "para o publico")
            .any(value::contains)
    }

    fun isExplicitMemoryRecall(text: String): Boolean {
        val value = normalize(text)
        return listOf(
            "o que voce lembra de mim", "o que voce sabe sobre mim", "o que sabe de mim",
            "use o que voce sabe", "use minha memoria", "onde paramos", "me lembra",
        ).any(value::contains)
    }

    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9%]+"), " ")
        .trim()

    private val PUBLIC_MARKERS = listOf(
        "para o mundo", "pro mundo", "para publicar", "publicar", "postar", "rede social",
        "publico", "publica", "divulgar", "compartilhar", "para compartilhar", "para todos",
        "apresentar para outras pessoas", "apresente se para", "bio publica", "site da empresa",
    )
}
