package com.autoflow.automation.models

/**
 * Determines the order in which click points are executed.
 */
enum class ExecutionMode {
    /** Execute points in order: 1 → 2 → 3 → ... → N */
    SEQUENTIAL,

    /** Execute points in random order each cycle */
    RANDOM,

    /** Execute points in reverse: N → ... → 3 → 2 → 1 */
    REVERSE
}

/**
 * Tracks the current state of the automation engine.
 */
enum class EngineState {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED
}

/**
 * Configuration for an automation session.
 *
 * @param executionMode How points are ordered each cycle
 * @param repeatCount Number of times to repeat the full cycle (-1 for infinite)
 * @param clickIntervalMs Delay between each action in milliseconds
 * @param randomIntervalMin Minimum random delay (used when randomIntervalMax > 0)
 * @param randomIntervalMax Maximum random delay (0 = use fixed clickIntervalMs)
 * @param isInfinite If true, repeat indefinitely until manually stopped
 */
data class AutomationConfig(
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    val repeatCount: Int = 1,
    val clickIntervalMs: Long = 500L,
    val randomIntervalMin: Long = 0L,
    val randomIntervalMax: Long = 0L,
    val isInfinite: Boolean = false
) {
    /** Whether random interval is enabled */
    val isRandomInterval: Boolean
        get() = randomIntervalMax > 0L && randomIntervalMax > randomIntervalMin
}
