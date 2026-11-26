package com.ultron.ai.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentPolicyTest {
    @Test fun keyAloneIsNotConsent() { assertFalse(ConsentPolicy.canSendChat(false, true)); assertFalse(ConsentPolicy.canSendAudio(false, true)) }
    @Test fun consentWithoutKeyCannotSend() = assertFalse(ConsentPolicy.canSendChat(true, false))
    @Test fun consentAndKeyAllowsChat() = assertTrue(ConsentPolicy.canSendChat(true, true))
    @Test fun screenTextNeedsItsOwnSwitch() { assertFalse(ConsentPolicy.canSendScreenText(true, false, true)); assertTrue(ConsentPolicy.canSendScreenText(true, true, true)) }
    @Test fun revokedCloudBlocksScreenText() = assertFalse(ConsentPolicy.canSendScreenText(false, true, true))
    @Test fun cloudOffWithdrawsScreenConsent() { assertFalse(ConsentPolicy.screenConsentAfterCloudChange(false, true)); assertTrue(ConsentPolicy.screenConsentAfterCloudChange(true, true)) }
}

class ConfirmationHardeningTest {
    @Test fun echoedQuestionIsNotAConfirmation() {
        org.junit.Assert.assertEquals(null, AgentProtocol.yesNo("haan ya nahi bolo"))
        org.junit.Assert.assertEquals(null, AgentProtocol.yesNo("send dabane wala hoon karun haan ya nahi bolo"))
    }
    @Test fun shortRealAnswersWork() {
        org.junit.Assert.assertEquals(true, AgentProtocol.yesNo("haan"))
        org.junit.Assert.assertEquals(false, AgentProtocol.yesNo("nahi"))
        org.junit.Assert.assertEquals(true, AgentProtocol.yesNo("haan kar do"))
    }
    @Test fun moreRiskyLabels() {
        for (l in listOf("Add money", "Subscribe", "Approve", "Bhugtan karo", "Share", "Enable", "Withdraw")) assertTrue(l, RiskPolicy.needsConfirmation(l))
        assertFalse(RiskPolicy.needsConfirmation("Search"))
    }
}
