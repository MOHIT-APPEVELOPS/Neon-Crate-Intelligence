package com.neon.crateintelligence

import kotlin.math.pow
import kotlin.random.Random

object CrateProbabilityEngine {

    const val BASE_MYTHIC_RATE = 0.005 // 0.5%
    const val BASE_LEGENDARY_RATE = 0.035 // 3.5%
    const val PITY_THRESHOLD = 40

    data class SimulationResult(
        val isMythic: Boolean,
        val isLegendary: Boolean,
        val rollValue: Double
    )

    /**
     * Streak ke hisaab se live dynamic odds nikalta hai
     */
    fun calculateCurrentOdds(consecutiveNonMythics: Int): Double {
        if (consecutiveNonMythics <= 0) return BASE_MYTHIC_RATE * 100

        // Cumulative chance formula: 1 - (1 - p)^n
        val cumulativeChance = (1.0 - (1.0 - BASE_MYTHIC_RATE).pow(consecutiveNonMythics)) * 100

        // Pity scaling bonus agar streak 40 cross kar chuki ho
        val pityBonus = if (consecutiveNonMythics >= PITY_THRESHOLD) {
            (consecutiveNonMythics - PITY_THRESHOLD) * 0.45
        } else {
            0.0
        }

        return (cumulativeChance + pityBonus).coerceAtMost(99.5)
    }

    /**
     * Bina UC kharch kiye instant Monte Carlo test spin
     */
    fun simulateSpin(consecutiveNonMythics: Int): SimulationResult {
        val roll = Random.nextDouble(0.0, 100.0)
        val dynamicMythicThreshold = (BASE_MYTHIC_RATE * 100) + if (consecutiveNonMythics >= PITY_THRESHOLD) {
            (consecutiveNonMythics - PITY_THRESHOLD) * 0.3
        } else 0.0

        val isMythic = roll < dynamicMythicThreshold
        val isLegendary = !isMythic && (roll < (dynamicMythicThreshold + (BASE_LEGENDARY_RATE * 100)))

        return SimulationResult(isMythic, isLegendary, roll)
    }
}
