package com.wristscroll.remote.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GestureLogicTest {

    private var fakeTimeMs: Long = 10_000L
    private lateinit var classifier: TouchGestureClassifier
    private lateinit var rotary: RotaryAccumulator

    // Assume 2.0 px/dp density -> 35 dp = 70 px, touchSlop = 16 px
    private val dragThresholdPx = 70f
    private val touchSlopPx = 16f

    @Before
    fun setUp() {
        fakeTimeMs = 10_000L
        classifier = TouchGestureClassifier(
            dragThresholdPx = dragThresholdPx,
            touchSlopPx = touchSlopPx,
            clock = { fakeTimeMs }
        )
        rotary = RotaryAccumulator(
            cooldownMs = 300L,
            clock = { fakeTimeMs }
        )
    }

    // --- Touch: Drag Directions & 35 dp Threshold ---

    @Test
    fun dragUp_reaching35dpThreshold_sendsNextItemAndIgnoresRestOfPress() {
        assertEquals(TouchOutcome.None, classifier.onDown(100f, 100f))
        fakeTimeMs += 50L
        // Move 69 px up (just below 70 px = 35 dp threshold) -> None
        assertEquals(TouchOutcome.None, classifier.onMove(100f, 31f))
        fakeTimeMs += 10L
        // Reach 70 px up -> immediately fires NEXT_ITEM
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_NEXT_ITEM),
            classifier.onMove(100f, 30f)
        )
        // Further movement and release on the same press produce nothing
        fakeTimeMs += 20L
        assertEquals(TouchOutcome.None, classifier.onMove(100f, 0f))
        fakeTimeMs += 20L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 0f))
    }

    @Test
    fun dragDown_reaching35dpThreshold_sendsPrevItem() {
        classifier.onDown(100f, 100f)
        fakeTimeMs += 40L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_PREV_ITEM),
            classifier.onMove(105f, 175f)
        )
        assertEquals(TouchOutcome.None, classifier.onUp(105f, 175f))
    }

    @Test
    fun dragLeft_reaching35dpThreshold_sendsSwipeLeft() {
        classifier.onDown(100f, 100f)
        fakeTimeMs += 40L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_SWIPE_LEFT),
            classifier.onMove(25f, 105f)
        )
        assertEquals(TouchOutcome.None, classifier.onUp(25f, 105f))
    }

    @Test
    fun dragRight_reaching35dpThreshold_sendsSwipeRight() {
        classifier.onDown(100f, 100f)
        fakeTimeMs += 40L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_SWIPE_RIGHT),
            classifier.onMove(175f, 95f)
        )
        assertEquals(TouchOutcome.None, classifier.onUp(175f, 95f))
    }

    // --- Touch: Single Tap & Double Tap ---

    @Test
    fun singleTap_firesSingleTapPauseAfter300msWindow() {
        classifier.onDown(100f, 100f)
        fakeTimeMs += 120L
        assertEquals(TouchOutcome.None, classifier.onUp(102f, 101f))

        // Before 300 ms window expires -> None
        fakeTimeMs += 299L
        assertEquals(TouchOutcome.None, classifier.onSingleTapTimeout())

        // At 300 ms -> SINGLE_TAP_PAUSE
        fakeTimeMs += 1L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE),
            classifier.onSingleTapTimeout()
        )
    }

    @Test
    fun singleTap_stillPendingWhenNextDownArrivesOutside300msWindow_returnsSingleTapPauseOnDown() {
        // First tap completes at t = 10_100
        classifier.onDown(100f, 100f)
        fakeTimeMs += 100L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))

        // 350 ms pass without onSingleTapTimeout being called yet; new press starts outside 300 ms window
        fakeTimeMs += 350L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE),
            classifier.onDown(100f, 100f)
        )

        // The new press itself resolves normally as a fresh tap after its own 300 ms window
        fakeTimeMs += 100L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))
        fakeTimeMs += 300L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE),
            classifier.onSingleTapTimeout()
        )
    }

    @Test
    fun doubleTap_secondTapWithin300ms_sendsDoubleTapLikeOnly() {
        // First tap
        classifier.onDown(100f, 100f)
        fakeTimeMs += 100L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))

        // Second tap starts 180 ms after first tap ended
        fakeTimeMs += 180L
        assertEquals(TouchOutcome.None, classifier.onDown(102f, 98f))
        // Even if the 300 ms timer from the first tap ticks while second press is down, nothing fires
        fakeTimeMs += 150L
        assertEquals(TouchOutcome.None, classifier.onSingleTapTimeout())

        // Second tap released within 400 ms -> DOUBLE_TAP_LIKE
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_DOUBLE_TAP_LIKE),
            classifier.onUp(102f, 98f)
        )
        // Subsequent timeout check produces nothing
        fakeTimeMs += 300L
        assertEquals(TouchOutcome.None, classifier.onSingleTapTimeout())
    }

    // --- Touch: Rule 2.1 (All Four Cases when Second Press Starts Within 300 ms) ---

    @Test
    fun rule2_1_secondPressBecomesDrag_sendsDragCommandOnly() {
        // First tap
        classifier.onDown(100f, 100f)
        fakeTimeMs += 100L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))

        // Second press starts 150 ms later and drags up >= 70 px
        fakeTimeMs += 150L
        classifier.onDown(100f, 100f)
        fakeTimeMs += 60L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_NEXT_ITEM),
            classifier.onMove(100f, 25f)
        )
        fakeTimeMs += 50L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 25f))
        fakeTimeMs += 300L
        assertEquals(TouchOutcome.None, classifier.onSingleTapTimeout())
    }

    @Test
    fun rule2_1_secondPressBecomes1500msHold_exitsToDashboardOnly() {
        // First tap
        classifier.onDown(100f, 100f)
        fakeTimeMs += 100L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))

        // Second press starts 200 ms later and holds still for 1500 ms
        fakeTimeMs += 200L
        classifier.onDown(100f, 100f)
        fakeTimeMs += 1500L
        assertEquals(TouchOutcome.HoldExit, classifier.onHoldTimeout())
        fakeTimeMs += 50L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))
        fakeTimeMs += 300L
        assertEquals(TouchOutcome.None, classifier.onSingleTapTimeout())
    }

    @Test
    fun rule2_1_secondPressIsAbandonedStillPress400To1500ms_sendsFirstTapSingleTapPause() {
        // First tap
        classifier.onDown(100f, 100f)
        fakeTimeMs += 120L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))

        // Second press starts within 300 ms (at +200 ms)
        fakeTimeMs += 200L
        assertEquals(TouchOutcome.None, classifier.onDown(100f, 100f))

        // While finger is down at +350 ms after first tap up, single-tap timeout does not fire early
        fakeTimeMs += 150L
        assertEquals(TouchOutcome.None, classifier.onSingleTapTimeout())

        // Second press is held still for 700 ms total (between 400 ms and 1500 ms) and released
        fakeTimeMs += 550L
        assertEquals(
            TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE),
            classifier.onUp(100f, 100f)
        )
    }

    // --- Touch: 1500 ms Hold & 400–1500 ms Abandoned First Press ---

    @Test
    fun holdFor1500msWhileDown_returnsHoldExitImmediately() {
        classifier.onDown(100f, 100f)
        fakeTimeMs += 1499L
        assertEquals(TouchOutcome.None, classifier.onHoldTimeout())
        fakeTimeMs += 1L
        assertEquals(TouchOutcome.HoldExit, classifier.onHoldTimeout())
        // Releasing finger later does nothing
        fakeTimeMs += 200L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))
    }

    @Test
    fun abandonedFirstPressBetween400And1500ms_doesNothing() {
        classifier.onDown(100f, 100f)
        fakeTimeMs += 800L
        assertEquals(TouchOutcome.None, classifier.onUp(100f, 100f))
        fakeTimeMs += 400L
        assertEquals(TouchOutcome.None, classifier.onSingleTapTimeout())
    }

    // --- Rotary: Thresholds per Sensitivity, Direction Reversal, and 300 ms Cooldown ---

    @Test
    fun rotaryThresholds_high24_medium48_low80_fireExpectedCommands() {
        // High (24 px) in Feed mode
        assertNull(rotary.onScroll(deltaPixels = 12f, thresholdPx = 24f, isCarouselMode = false))
        assertEquals(
            Protocol.CMD_NEXT_ITEM,
            rotary.onScroll(deltaPixels = 12f, thresholdPx = 24f, isCarouselMode = false)
        )

        // Advance past 300 ms cooldown; test Medium (48 px) negative in Feed mode
        fakeTimeMs += 301L
        assertNull(rotary.onScroll(deltaPixels = -30f, thresholdPx = 48f, isCarouselMode = false))
        assertEquals(
            Protocol.CMD_PREV_ITEM,
            rotary.onScroll(deltaPixels = -20f, thresholdPx = 48f, isCarouselMode = false)
        )

        // Advance past 300 ms cooldown; test Low (80 px) in Carousel mode
        fakeTimeMs += 301L
        assertNull(rotary.onScroll(deltaPixels = 50f, thresholdPx = 80f, isCarouselMode = true))
        assertEquals(
            Protocol.CMD_SWIPE_LEFT,
            rotary.onScroll(deltaPixels = 35f, thresholdPx = 80f, isCarouselMode = true)
        )
    }

    @Test
    fun rotaryDirectionReversal_resetsRunningTotal() {
        // Accumulate +40 px toward 48 px threshold
        assertNull(rotary.onScroll(deltaPixels = 40f, thresholdPx = 48f, isCarouselMode = false))
        assertEquals(40f, rotary.runningTotal, 0.01f)

        // Reverse direction with -15 px -> resets +40 px and starts at -15 px
        assertNull(rotary.onScroll(deltaPixels = -15f, thresholdPx = 48f, isCarouselMode = false))
        assertEquals(-15f, rotary.runningTotal, 0.01f)

        // Reverse back to +20 px -> resets -15 px and starts at +20 px (does not fire yet)
        assertNull(rotary.onScroll(deltaPixels = 20f, thresholdPx = 48f, isCarouselMode = false))
        assertEquals(20f, rotary.runningTotal, 0.01f)

        // Add +28 px -> reaches +48 px and fires NEXT_ITEM
        assertEquals(
            Protocol.CMD_NEXT_ITEM,
            rotary.onScroll(deltaPixels = 28f, thresholdPx = 48f, isCarouselMode = false)
        )
    }

    @Test
    fun rotaryCooldown300ms_ignoresEventsAndDoesNotAccumulateThem() {
        assertEquals(
            Protocol.CMD_SWIPE_RIGHT,
            rotary.onScroll(deltaPixels = -24f, thresholdPx = 24f, isCarouselMode = true)
        )

        // Events at +100 ms and +299 ms during cooldown are ignored and not added to runningTotal
        fakeTimeMs += 100L
        assertNull(rotary.onScroll(deltaPixels = -20f, thresholdPx = 24f, isCarouselMode = true))
        assertEquals(0f, rotary.runningTotal, 0.01f)

        fakeTimeMs += 199L
        assertNull(rotary.onScroll(deltaPixels = -20f, thresholdPx = 24f, isCarouselMode = true))
        assertEquals(0f, rotary.runningTotal, 0.01f)

        // At +300 ms cooldown expires -> new scroll starts fresh from 0
        fakeTimeMs += 1L
        assertNull(rotary.onScroll(deltaPixels = -10f, thresholdPx = 24f, isCarouselMode = true))
        assertEquals(-10f, rotary.runningTotal, 0.01f)
    }
}
