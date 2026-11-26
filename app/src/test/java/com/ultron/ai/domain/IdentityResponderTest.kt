package com.ultron.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityResponderTest {
    private val r = IdentityResponder("Fardeen")

    @Test fun `creator question`() {
        assertEquals("Mujhe Fardeen ne banaya hai.", r.respond("Tujhe kisne banaya?"))
        assertEquals("Mujhe Fardeen ne banaya hai.", r.respond("Who created you"))
    }

    @Test fun `challenge keeps configured-record wording`() {
        assertEquals(
            "Mere configured creator record ke mutabik mujhe Fardeen ne develop kiya hai.",
            r.respond("Maine tujhe banaya hai"),
        )
    }

    @Test fun `unrelated text is not identity`() {
        assertNull(r.respond("kal ka weather kaisa rahega"))
    }
}
