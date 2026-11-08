package com.listentogether.testing

import com.listentogether.core.common.AppResult
import com.listentogether.core.model.Role
import com.listentogether.core.model.TurnCredentials
import com.listentogether.voice.VoiceSession
import com.listentogether.voice.VoiceSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceScenariosTest {
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private class Harness(
        val transport: RecordingVoiceTransport,
        val signaling: RecordingVoiceSignaling,
        val session: VoiceSession,
    )

    private fun harness(
        scope: CoroutineScope,
        turn: AppResult<TurnCredentials> = AppResult.Success(
            TurnCredentials(
                username = "turn-user",
                credential = "turn-secret",
                ttlSeconds = 600,
                urls = listOf("turn:turn.example.com:3478"),
            ),
        ),
    ): Harness {
        val transport = RecordingVoiceTransport()
        val signaling = RecordingVoiceSignaling()
        val session = VoiceSession(
            transport = transport,
            signaling = signaling,
            turnCredentials = { turn },
            clock = VirtualClock(),
            scope = scope,
        )
        return Harness(transport, signaling, session)
    }

    @Test
    fun `speaker offers once and starts muted`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.SPEAKER)
        advanceUntilIdle()

        assertEquals(Role.SPEAKER, harness.transport.startedRole)
        assertEquals(1, harness.transport.startCount)
        assertTrue(harness.transport.micMuted)
        assertEquals(1, harness.signaling.sent.filterIsInstance<VoiceSignal.Offer>().size)
        harness.session.close()
    }

    @Test
    fun `listener never offers and answers a remote offer`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.LISTENER)
        advanceUntilIdle()
        assertTrue(harness.signaling.sent.none { it is VoiceSignal.Offer })

        harness.signaling.receive(VoiceSignal.Offer("remote-offer"))
        advanceUntilIdle()

        assertEquals(
            listOf(com.listentogether.voice.VoiceDescriptionType.OFFER to "remote-offer"),
            harness.transport.remoteDescriptions,
        )
        assertEquals(1, harness.signaling.sent.filterIsInstance<VoiceSignal.Answer>().size)
        harness.session.close()
    }

    @Test
    fun `mic mute toggles the track without renegotiating`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.SPEAKER)
        advanceUntilIdle()
        val offersBefore = harness.signaling.sent.count { it is VoiceSignal.Offer }
        val startsBefore = harness.transport.startCount

        harness.session.setMicMuted(false)
        runCurrent()
        assertEquals(false, harness.transport.micMuted)
        harness.session.setMicMuted(true)
        advanceUntilIdle()

        assertEquals(true, harness.transport.micMuted)
        assertEquals(startsBefore, harness.transport.startCount)
        assertEquals(offersBefore, harness.signaling.sent.count { it is VoiceSignal.Offer })
        harness.session.close()
    }

    @Test
    fun `speaker restarts ice immediately on failure`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.SPEAKER)
        advanceUntilIdle()

        harness.transport.failIce()
        runCurrent()

        assertEquals(1, harness.transport.restartCount)
        harness.session.close()
    }

    @Test
    fun `listener restarts ice only after fifteen seconds of failure`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.LISTENER)
        advanceUntilIdle()

        harness.transport.failIce()
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(0, harness.transport.restartCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, harness.transport.restartCount)
        harness.session.close()
    }

    @Test
    fun `ice recovery cancels the pending listener restart`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.LISTENER)
        advanceUntilIdle()

        harness.transport.failIce()
        runCurrent()
        harness.transport.connectIce()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(0, harness.transport.restartCount)
        harness.session.close()
    }

    @Test
    fun `turn credentials are fetched at start and never embedded`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.SPEAKER)
        advanceUntilIdle()

        val urls = harness.transport.iceServers.flatMap { it.urls }
        assertTrue(urls.any { it.startsWith("stun:") })
        assertTrue(urls.contains("turn:turn.example.com:3478"))
        val turn = harness.transport.iceServers.first { "turn:turn.example.com:3478" in it.urls }
        assertEquals("turn-user", turn.username)
        assertEquals("turn-secret", turn.credential)
        harness.session.close()
    }

    @Test
    fun `remote ice candidates are applied and close tears down both seams`() = runTest {
        val scope = scope()
        val harness = harness(scope)
        harness.session.start(Role.LISTENER)
        advanceUntilIdle()

        harness.signaling.receive(VoiceSignal.Ice("audio", 0, "candidate:1"))
        advanceUntilIdle()
        assertEquals(
            listOf(Triple<String?, Int, String>("audio", 0, "candidate:1")),
            harness.transport.remoteCandidates,
        )

        harness.session.close()
        assertEquals(1, harness.transport.closeCount)
        assertTrue(harness.signaling.closed)
    }
}
