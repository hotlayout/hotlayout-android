package com.hotlayout.render.widgets

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hotlayout.JsonValue
import com.hotlayout.render.modal.LocalScreenInFront
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the carousel tells the timer about the row at this composition. */
internal class CarouselAutoAdvanceInput(
    val config: CarouselAutoAdvance,
    /** The stop the row stands on now and the number of stops; `null` while the stops are not laid out. */
    val view: () -> CarouselStopView?,
    /** Keyboard focus is on the row or something inside it. */
    val keyboardFocus: () -> Boolean,
    val path: String?,
    /** The value under the path now. */
    val value: () -> JsonValue?,
    val write: ((String, JsonValue) -> Unit)?,
)

/**
 * The timer of a carousel that advances by time: a clock of 100 ms, the pauses that freeze its count (a finger on the row,
 * keyboard focus inside it, a scroll still moving, a screen that is not in front, an app that is not in front, a screen
 * reader) and the step it makes. The step is the reader's own move: the tracker never takes it for a person, so its settle
 * writes nothing, and the new stop is written to the path once, by the step itself.
 */
@Composable
internal fun CarouselAutoAdvanceEffects(driver: CarouselAutoAdvanceDriver, tracker: CarouselStopTracker, scroll: ScrollState, input: CarouselAutoAdvanceInput) {
    val current by rememberUpdatedState(input)
    val inFront = LocalScreenInFront.current
    val appActive = rememberAppInFront()
    val exploring = rememberTouchExploration()
    val scope = rememberCoroutineScope()
    SideEffect {
        driver.configure(input.config)
        driver.view = { current.view() }
        driver.onStep = { step ->
            driver.scrollMoved()
            tracker.markHeading(step)
            scope.launch { tracker.go(scroll, step) }
            val path = current.path
            if (path != null) CarouselAutoAdvance.write(step, current.value())?.let { current.write?.invoke(path, JsonValue.Num(it.toDouble())) }
        }
    }
    val shown by rememberUpdatedState(inFront && appActive)
    val reader by rememberUpdatedState(exploring)
    LaunchedEffect(scroll, driver) {
        snapshotFlow { scroll.value }.collect { driver.scrollMoved() }
    }
    LaunchedEffect(driver) {
        while (true) {
            delay(CarouselAutoAdvance.TICK_MS)
            driver.set(CarouselAutoAdvance.Pause.Held, tracker.isHolding)
            driver.set(CarouselAutoAdvance.Pause.KeyboardFocus, current.keyboardFocus())
            driver.set(CarouselAutoAdvance.Pause.Moving, scroll.isScrollInProgress)
            driver.set(CarouselAutoAdvance.Pause.NotShown, !shown)
            driver.set(CarouselAutoAdvance.Pause.ScreenReader, reader)
            driver.tick()
        }
    }
}

/** Whether the app is in front: it leaves that on a call, the lock screen and the app switcher (`ON_PAUSE`). */
@Composable
private fun rememberAppInFront(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var inFront by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) inFront = false
            if (event == Lifecycle.Event.ON_RESUME) inFront = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return inFront
}

/** Whether a screen reader is touching the screen for the person (touch exploration). */
@Composable
private fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    var exploring by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return exploring
}
