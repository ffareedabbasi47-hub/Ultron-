package com.ultron.ai.voice

import com.ultron.ai.domain.live.LiveState
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStatesTest {
    @Test fun everyLiveStateMapsToOneVoiceState() {
        val expected = mapOf(
            LiveState.STOPPED to VoiceState.IDLE, LiveState.STARTING to VoiceState.IDLE, LiveState.SLEEP to VoiceState.IDLE,
            LiveState.LISTENING to VoiceState.LISTENING, LiveState.USER_SPEECH to VoiceState.LISTENING,
            LiveState.THINKING to VoiceState.PROCESSING, LiveState.SPEAKING to VoiceState.SPEAKING,
            LiveState.PAUSED to VoiceState.PAUSED,
            LiveState.PERMISSION_REQUIRED to VoiceState.ERROR, LiveState.AUDIO_ERROR to VoiceState.ERROR,
        )
        for (s in LiveState.values()) assertEquals(s.name, expected.getValue(s), VoiceStates.of(s))
        assertEquals(LiveState.values().size, expected.size)
    }
}
