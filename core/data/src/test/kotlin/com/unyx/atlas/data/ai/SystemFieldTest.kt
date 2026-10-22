package com.unyx.atlas.data.ai

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.Test

class SystemFieldTest {

    @Test
    fun `without volatile context the system prompt stays a plain string`() {
        assertThat(buildSystemField("instruções", null)).isEqualTo("instruções")
        assertThat(buildSystemField("instruções", "  ")).isEqualTo("instruções")
    }

    @Test
    fun `with volatile context only the stable block carries the cache breakpoint`() {
        val field = buildSystemField("instruções fixas", "Data e hora atual: 19:32. Bateria 63%.") as JSONArray

        assertThat(field.length()).isEqualTo(2)
        val stable = field.getJSONObject(0)
        assertThat(stable.getString("text")).isEqualTo("instruções fixas")
        assertThat(stable.getJSONObject("cache_control").getString("type")).isEqualTo("ephemeral")
        val volatile = field.getJSONObject(1)
        assertThat(volatile.getString("text")).contains("19:32")
        assertThat(volatile.has("cache_control")).isFalse()
    }

    @Test
    fun `the cached block does not change when only the volatile part does`() {
        val first = buildSystemField("instruções fixas", "19:32") as JSONArray
        val second = buildSystemField("instruções fixas", "19:33, memória diferente") as JSONArray

        assertThat(first.getJSONObject(0).toString()).isEqualTo(second.getJSONObject(0).toString())
        assertThat(first.getJSONObject(1).toString()).isNotEqualTo(second.getJSONObject(1).toString())
    }

    @Test
    fun `a volatile-only prompt is sent uncached and nothing at all is sent when both are blank`() {
        val onlyVolatile = buildSystemField(null, "contexto") as JSONArray
        assertThat(onlyVolatile.length()).isEqualTo(1)
        assertThat(onlyVolatile.getJSONObject(0).has("cache_control")).isFalse()

        assertThat(buildSystemField(null, null)).isNull()
        assertThat(buildSystemField("", " ")).isNull()
    }
}
