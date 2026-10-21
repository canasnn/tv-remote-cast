package com.ruterika.castcore.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhonePlayerSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun oldFileWithoutPhonePlayerLoadsDefaults() {
        val file = tmp.root.resolve("settings.json").apply { writeText("""{"skipBackSeconds":10,"phoneGestures":false}""") }
        val s = CastSettingsStore(file).current
        assertEquals(10, s.skipBackSeconds)
        assertEquals(PhonePlayerSettings(), s.phonePlayer)
    }

    @Test
    fun persistsPhonePlayerChanges() {
        val file = tmp.root.resolve("settings.json")
        CastSettingsStore(file).update { it.copy(phonePlayer = it.phonePlayer.copy(volumeBoost = true, volumeLevel = 1.5f, cacheMb = 0)) }
        val p = CastSettingsStore(file).current.phonePlayer
        assertEquals(true, p.volumeBoost)
        assertEquals(1.5f, p.volumeLevel)
        assertEquals(0, p.cacheMb)
    }

    @Test
    fun boostGainMapsAboveUnity() {
        val p = PhonePlayerSettings(volumeBoost = true, boostMaxDb = 6)
        assertEquals(0, p.boostGainMb(1f))
        assertEquals(300, p.boostGainMb(1.5f))
        assertEquals(600, p.boostGainMb(2f))
        assertEquals(600, p.boostGainMb(3f))
        assertEquals(0, p.copy(volumeBoost = false).boostGainMb(2f))
        assertEquals(1f, p.copy(volumeBoost = false).maxVolume)
        assertEquals(2f, p.maxVolume)
    }

    @Test
    fun bufferDurationsAreClamped() {
        assertArrayEquals(intArrayOf(50_000, 50_000, 1_000, 2_000), PhonePlayerSettings().bufferDurationsMs)
        assertArrayEquals(intArrayOf(5_000, 5_000, 1_000, 2_000), PhonePlayerSettings(bufferSeconds = 1).bufferDurationsMs)
    }
}
