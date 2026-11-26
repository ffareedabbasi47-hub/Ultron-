package com.ultron.ai.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPolicyTest {
    @Test fun externalIntentWithoutTokenCannotStartMic() = assertFalse(LaunchPolicy.autoStartAllowed(null, "secret"))
    @Test fun wrongTokenCannotStartMic() = assertFalse(LaunchPolicy.autoStartAllowed("guess", "secret"))
    @Test fun emptyTokensNeverMatch() { assertFalse(LaunchPolicy.autoStartAllowed("", "")); assertFalse(LaunchPolicy.autoStartAllowed("x", null)) }
    @Test fun ownTokenAllowed() { val t = LaunchPolicy.newToken(); assertTrue(LaunchPolicy.autoStartAllowed(t, t)) }
    @Test fun tokensAreUnique() = assertFalse(LaunchPolicy.newToken() == LaunchPolicy.newToken())
}
