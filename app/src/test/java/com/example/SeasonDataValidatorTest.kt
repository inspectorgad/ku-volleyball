package com.example

import com.example.data.SeasonDataValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeasonDataValidatorTest {

    private fun payload(players: Int, matches: Int, formatVersion: Int = 1): ByteArray {
        val root = JSONObject()
        root.put("formatVersion", formatVersion)
        root.put("players", JSONArray().apply {
            repeat(players) { put(JSONObject().put("name", "Player $it")) }
        })
        root.put("matches", JSONArray().apply {
            repeat(matches) {
                put(JSONObject().put("date", "2025-09-%02d".format(it + 1))
                    .put("opponent", "Opp").put("season", "2025"))
            }
        })
        return root.toString().toByteArray()
    }

    @Test
    fun `accepts a plausible payload`() {
        assertNotNull(SeasonDataValidator.parse(payload(players = 15, matches = 30)))
    }

    @Test
    fun `rejects payload with too few players or matches`() {
        assertNull(SeasonDataValidator.parse(payload(players = 2, matches = 30)))
        assertNull(SeasonDataValidator.parse(payload(players = 15, matches = 3)))
    }

    @Test
    fun `rejects future format versions`() {
        assertNull(SeasonDataValidator.parse(payload(players = 15, matches = 30, formatVersion = 2)))
    }

    @Test
    fun `rejects truncated and garbage payloads`() {
        val valid = payload(players = 15, matches = 30)
        assertNull(SeasonDataValidator.parse(valid.copyOf(valid.size / 2)))
        assertNull(SeasonDataValidator.parse("not json at all".toByteArray()))
        assertNull(SeasonDataValidator.parse(ByteArray(0)))
    }
}
