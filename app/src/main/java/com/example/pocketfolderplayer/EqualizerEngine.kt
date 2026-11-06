package com.example.pocketfolderplayer

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import kotlin.math.roundToInt

/** Lives with the playback service, so audio effects survive closing the UI. */
class EqualizerEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("pocket_equalizer", Context.MODE_PRIVATE)
    private var effect: Equalizer? = null
    private var sessionId = -1

    var supported by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(prefs.getBoolean("enabled", true))
        private set
    var preset by mutableStateOf(prefs.getString("preset", "Flat") ?: "Flat")
        private set
    var bandLevels by mutableStateOf<List<Int>>(emptyList())
        private set
    var frequencies by mutableStateOf<List<String>>(emptyList())
        private set

    val presets = listOf("Flat", "Bass", "Rock", "Pop", "Classical")
    private val presetsFiveBands = mapOf(
        "Flat" to listOf(50, 50, 50, 50, 50),
        "Bass" to listOf(86, 74, 55, 46, 42),
        "Rock" to listOf(70, 60, 42, 61, 73),
        "Pop" to listOf(53, 63, 73, 62, 52),
        "Classical" to listOf(68, 58, 45, 58, 71)
    )

    /** An audio session can change when the player rebuilds its audio output. */
    fun attach(newSessionId: Int) {
        if (newSessionId <= 0 || (sessionId == newSessionId && effect != null)) return
        effect?.release()
        effect = null
        sessionId = newSessionId
        supported = false
        try {
            val newEffect = Equalizer(0, newSessionId)
            effect = newEffect
            val count = newEffect.numberOfBands.toInt()
            if (count <= 0) {
                newEffect.release()
                effect = null
                return
            }
            frequencies = (0 until count).map { i ->
                val hertz = newEffect.getCenterFreq(i.toShort()) / 1000
                if (hertz >= 1000) "${"%.1f".format(hertz / 1000f)}kHz" else "${hertz}Hz"
            }
            supported = true
            val storedBands = runCatching {
                JSONArray(prefs.getString("bands", "[]")).let { arr ->
                    (0 until arr.length()).map { arr.getInt(it).coerceIn(0, 100) }
                }
            }.getOrDefault(emptyList())
            bandLevels = if (preset == "Custom" && storedBands.size == count) {
                storedBands
            } else {
                bandValuesFor(preset, count)
            }
            applyBands()
            newEffect.enabled = enabled
        } catch (_: Exception) {
            runCatching { effect?.release() }
            effect = null
            frequencies = emptyList()
            bandLevels = emptyList()
            supported = false
        }
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
        runCatching { effect?.enabled = value }
    }

    fun choosePreset(name: String) {
        if (name !in presets) return
        preset = name
        bandLevels = bandValuesFor(name, bandLevels.size)
        applyBands()
        persist()
    }

    fun setBand(index: Int, level: Int) {
        if (index !in bandLevels.indices) return
        bandLevels = bandLevels.toMutableList().also { it[index] = level.coerceIn(0, 100) }
        preset = "Custom"
        applyBands()
        persist()
    }

    private fun bandValuesFor(name: String, count: Int): List<Int> {
        val source = presetsFiveBands[name] ?: presetsFiveBands.getValue("Flat")
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(source[2])
        return (0 until count).map { i ->
            val position = i.toFloat() * (source.size - 1) / (count - 1)
            val low = position.toInt()
            val high = (low + 1).coerceAtMost(source.lastIndex)
            (source[low] + (source[high] - source[low]) * (position - low)).roundToInt()
        }
    }

    private fun applyBands() {
        val eq = effect ?: return
        runCatching {
            val range = eq.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            bandLevels.forEachIndexed { i, level ->
                val actual = min + ((max - min) * level / 100f).roundToInt()
                eq.setBandLevel(i.toShort(), actual.toShort())
            }
        }
    }

    private fun persist() {
        val array = JSONArray()
        bandLevels.forEach { array.put(it) }
        prefs.edit().putString("preset", preset).putString("bands", array.toString()).apply()
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
        sessionId = -1
    }
}
