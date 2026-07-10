package com.autoflow.automation.engine

import android.util.Log
import com.autoflow.automation.models.ActionType
import com.autoflow.automation.models.AutomationConfig
import com.autoflow.automation.models.ClickPoint
import com.autoflow.automation.models.EngineState
import com.autoflow.automation.models.ExecutionMode
import com.autoflow.core.service.AutoFlowAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The automation orchestrator. Takes a list of [ClickPoint]s and an
 * [AutomationConfig], then executes the points in the configured order
 * with the configured timing.
 *
 * Supports:
 * - Sequential, Random, and Reverse execution modes
 * - Fixed or random click intervals
 * - Finite repeat count or infinite loop
 * - Pause / Resume / Stop controls
 *
 * Usage:
 * ```
 * val engine = ClickEngine()
 * engine.start(points, config, scope)
 * // Later...
 * engine.pause()
 * engine.resume()
 * engine.stop()
 * ```
 */
class ClickEngine {

    companion object {
        private const val TAG = "ClickEngine"
    }

    private var job: Job? = null

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _isPaused = MutableStateFlow(false)

    private val _totalClicks = MutableStateFlow(0)
    val totalClicks: StateFlow<Int> = _totalClicks.asStateFlow()

    private val _currentIteration = MutableStateFlow(0)
    val currentIteration: StateFlow<Int> = _currentIteration.asStateFlow()

    /**
     * Starts the automation engine.
     *
     * @param points The click points to execute
     * @param config The automation configuration
     * @param scope The coroutine scope to run in (typically from the OverlayService)
     */
    fun start(
        points: List<ClickPoint>,
        config: AutomationConfig,
        scope: CoroutineScope
    ) {
        if (points.isEmpty()) {
            Log.w(TAG, "No click points to execute")
            return
        }

        stop() // Cancel any existing run

        _totalClicks.value = 0
        _currentIteration.value = 0

        job = scope.launch(Dispatchers.Default) {
            _state.value = EngineState.RUNNING
            Log.i(TAG, "Engine started with ${points.size} points, mode=${config.executionMode}")

            var iteration = 0

            while (isActive && (config.isInfinite || iteration < config.repeatCount)) {
                _currentIteration.value = iteration + 1

                val orderedPoints = when (config.executionMode) {
                    ExecutionMode.SEQUENTIAL -> points.sortedBy { it.order }
                    ExecutionMode.RANDOM -> points.shuffled()
                    ExecutionMode.REVERSE -> points.sortedByDescending { it.order }
                }

                for (point in orderedPoints) {
                    if (!isActive) break

                    // Handle pause — suspend until resumed
                    while (_isPaused.value && isActive) {
                        delay(100)
                    }
                    if (!isActive) break

                    executeAction(point)

                    // Calculate delay to next action
                    val interval = if (config.isRandomInterval) {
                        Random.nextLong(config.randomIntervalMin, config.randomIntervalMax + 1)
                    } else {
                        config.clickIntervalMs
                    }
                    delay(interval)
                }

                iteration++
            }

            if (isActive) {
                _state.value = EngineState.COMPLETED
                Log.i(TAG, "Engine completed. Total clicks: ${_totalClicks.value}")
            }
        }
    }

    /**
     * Pauses the automation. Actions in progress will complete,
     * but no new actions will be dispatched until resumed.
     */
    fun pause() {
        if (_state.value == EngineState.RUNNING) {
            _isPaused.value = true
            _state.value = EngineState.PAUSED
            Log.i(TAG, "Engine paused")
        }
    }

    /**
     * Resumes a paused automation.
     */
    fun resume() {
        if (_state.value == EngineState.PAUSED) {
            _isPaused.value = false
            _state.value = EngineState.RUNNING
            Log.i(TAG, "Engine resumed")
        }
    }

    /**
     * Stops the automation completely. This cancels the coroutine
     * and resets the engine state to IDLE.
     */
    fun stop() {
        job?.cancel()
        job = null
        _isPaused.value = false
        _state.value = EngineState.IDLE
        Log.i(TAG, "Engine stopped. Total clicks: ${_totalClicks.value}")
    }

    /**
     * Executes a single action at a click point.
     */
    private suspend fun executeAction(point: ClickPoint) {
        val service = AutoFlowAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "AccessibilityService not connected, cannot execute action")
            stop()
            return
        }

        when (point.actionType) {
            ActionType.CLICK -> {
                service.performClick(point.x, point.y, point.duration)
                _totalClicks.value++
                Log.d(TAG, "Click at (${point.x}, ${point.y})")
            }

            ActionType.LONG_PRESS -> {
                service.performLongPress(point.x, point.y, point.duration)
                _totalClicks.value++
                Log.d(TAG, "Long press at (${point.x}, ${point.y}) for ${point.duration}ms")
            }

            ActionType.SWIPE -> {
                service.performSwipe(
                    point.x, point.y,
                    point.endX, point.endY,
                    point.duration
                )
                _totalClicks.value++
                Log.d(TAG, "Swipe from (${point.x}, ${point.y}) to (${point.endX}, ${point.endY})")
            }

            ActionType.WAIT -> {
                Log.d(TAG, "Waiting ${point.duration}ms")
                delay(point.duration)
            }
        }
    }
}
