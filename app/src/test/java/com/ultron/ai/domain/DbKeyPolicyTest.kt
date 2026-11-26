package com.ultron.ai.domain

import com.ultron.ai.domain.DbKeyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DbKeyPolicyTest {
    @Test fun readableKeyOpensExisting() = assertEquals(Action.USE_EXISTING, DbKeyPolicy.decide(true, true, true))
    @Test fun firstRunCreates() = assertEquals(Action.CREATE_NEW, DbKeyPolicy.decide(false, false, false))
    @Test fun unreadableKeyWithDbNeverDeletes() = assertEquals(Action.QUARANTINE_AND_CREATE, DbKeyPolicy.decide(true, false, true))
    @Test fun missingKeyButDbExistsIsQuarantined() = assertEquals(Action.QUARANTINE_AND_CREATE, DbKeyPolicy.decide(false, false, true))
    @Test fun unreadableKeyWithoutDbCreatesNewWithoutLoss() = assertEquals(Action.QUARANTINE_AND_CREATE, DbKeyPolicy.decide(true, false, false))
    @Test fun quarantineNameNeverCollidesWithLive() {
        val n = DbKeyPolicy.quarantineName("ultron.db", "-wal", 5L)
        assertNotEquals("ultron.db", n); assertEquals("ultron.db.unreadable-5-wal", n)
    }
}
