package com.listentogether.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioArbiterTest {
    private class RecordingFocus : FocusController {
        var requests = 0
        var abandons = 0
        var granted = true
        override fun requestFocus(): Boolean {
            requests++
            return granted
        }

        override fun abandonFocus() {
            abandons++
        }
    }

    private class RecordingRoute : RouteController {
        var entered = 0
        var exited = 0
        override fun enterCommunicationMode() {
            entered++
        }

        override fun exitCommunicationMode() {
            exited++
        }
    }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun arbiter(
        scope: CoroutineScope,
        focus: RecordingFocus = RecordingFocus(),
        route: RecordingRoute = RecordingRoute(),
        volumes: MutableList<Float> = mutableListOf(),
    ) = AudioArbiter(focus, route, volumeSink = { volumes += it }, scope = scope)

    @Test
    fun `voice outranks tts which outranks idle`() = runTest {
        val volumes = mutableListOf<Float>()
        val arbiter = arbiter(scope(), volumes = volumes)

        arbiter.setMusicActive(true)
        assertEquals(1.0f, arbiter.state.value.musicVolume)

        arbiter.setTtsActive(true)
        assertEquals(0.35f, arbiter.state.value.musicVolume)

        arbiter.setVoiceActive(true)
        assertEquals(0.25f, arbiter.state.value.musicVolume)

        arbiter.setVoiceActive(false)
        arbiter.setTtsActive(false)
        assertEquals(1.0f, arbiter.state.value.musicVolume)
        assertEquals(listOf(0.35f, 0.25f, 0.35f, 1.0f), volumes)
    }

    @Test
    fun `focus is requested once and abandoned when music stops`() = runTest {
        val focus = RecordingFocus()
        val arbiter = arbiter(scope(), focus)

        arbiter.setMusicActive(true)
        arbiter.setMusicActive(true)
        assertTrue(arbiter.state.value.focusGranted)
        assertEquals(1, focus.requests)

        arbiter.setMusicActive(false)
        assertEquals(1, focus.abandons)
        assertFalse(arbiter.state.value.focusGranted)
    }

    @Test
    fun `tts gate stays closed for 600 ms after voice stops`() = runTest {
        val arbiter = arbiter(scope())
        arbiter.setVoiceActive(true)
        assertFalse(arbiter.state.value.ttsGateOpen)

        arbiter.setVoiceActive(false)
        assertFalse(arbiter.state.value.ttsGateOpen)
        advanceTimeBy(599)
        runCurrent()
        assertFalse(arbiter.state.value.ttsGateOpen)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(arbiter.state.value.ttsGateOpen)
    }

    @Test
    fun `voice restart cancels pending gate opening`() = runTest {
        val arbiter = arbiter(scope())
        arbiter.setVoiceActive(true)
        arbiter.setVoiceActive(false)
        advanceTimeBy(400)

        arbiter.setVoiceActive(true)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(arbiter.state.value.ttsGateOpen)
    }

    @Test
    fun `external transient loss is surfaced without auto resume`() = runTest {
        val focus = RecordingFocus()
        val scope = scope()
        val arbiter = arbiter(scope, focus)
        val recorded = mutableListOf<AudioEvent>()
        val job = scope.launch { arbiter.events.collect { recorded += it } }
        runCurrent()
        arbiter.setMusicActive(true)

        arbiter.onFocusChange(ExternalFocusChange.LOSS_TRANSIENT)
        runCurrent()
        assertFalse(arbiter.state.value.focusGranted)
        assertEquals(AudioEvent.TransientFocusLoss, recorded.single())

        arbiter.onFocusChange(ExternalFocusChange.GAIN)
        runCurrent()
        assertTrue(arbiter.state.value.focusGranted)
        assertEquals(AudioEvent.FocusGained, recorded.last())
        job.cancel()
    }

    @Test
    fun `voice toggles communication route`() = runTest {
        val route = RecordingRoute()
        val arbiter = arbiter(scope(), route = route)

        arbiter.setVoiceActive(true)
        assertTrue(arbiter.state.value.communicationRoute)
        assertEquals(1, route.entered)

        arbiter.setVoiceActive(false)
        assertFalse(arbiter.state.value.communicationRoute)
        assertEquals(1, route.exited)
    }

    @Test
    fun `release abandons focus and routes`() = runTest {
        val focus = RecordingFocus()
        val route = RecordingRoute()
        val arbiter = arbiter(scope(), focus, route)
        arbiter.setMusicActive(true)
        arbiter.setVoiceActive(true)

        arbiter.release()

        assertEquals(1, focus.abandons)
        assertEquals(1, route.exited)
        assertFalse(arbiter.state.value.musicActive)
        assertFalse(arbiter.state.value.voiceActive)
    }
}
