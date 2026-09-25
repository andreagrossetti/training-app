package com.andreagrossetti.training.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class StripCodeFenceTest {
    @Test
    fun stripsFences() {
        val json = "{\n  \"title\": \"Uno\"\n}"
        assertEquals(json, LocalAi.stripCodeFence("```json\n$json\n```"))
        assertEquals(json, LocalAi.stripCodeFence("```\n$json\n```\n"))
        assertEquals(json, LocalAi.stripCodeFence("  $json  "))
        assertEquals("{\"a\": 1}", LocalAi.stripCodeFence("```json {\"a\": 1} ```"))
    }
}
