package com.ultron.ai.domain.live

import org.junit.Assert.*
import org.junit.Test

class LiveToolPipelineTest {
    @Test fun toolCallParsedWithNumbersBooleansAndNestedValues() {
        val j = """{"toolCall":{"functionCalls":[{"id":"c1","name":"set_alarm","args":{"hour":7,"minute":"30","tomorrow":true,"odd":{"x":1}}}]}}"""
        val t = LiveWire.parseCall(j)!!.tools.single()
        assertEquals("c1", t.id); assertEquals("set_alarm", t.name)
        assertEquals("7", t.args["hour"]); assertEquals("30", t.args["minute"]); assertEquals("true", t.args["tomorrow"]); assertEquals("", t.args["odd"])
    }
    @Test fun toolResponseCarriesIdNameAndRealResult() {
        val r = LiveWire.toolResponse("c1", "call_contact", "Papa ko call ki: dialer khula")
        assertTrue(r.contains("\"id\":\"c1\"")); assertTrue(r.contains("\"name\":\"call_contact\"")); assertTrue(r.contains("dialer khula"))
    }
    @Test fun allActionToolsAreRegisteredInSetup() {
        val s = LiveWire.setupCall("p", "Puck")
        listOf("call_contact", "call_number", "send_message", "set_alarm", "set_timer", "device_control", "remember", "web_search", "my_capabilities", "youtube_search", "phone_toggle", "open_app")
            .forEach { assertTrue("missing tool $it", s.contains("\"name\":\"$it\"")) }
    }
    @Test fun optionalParametersAreNotRequired() {
        val s = LiveWire.setupCall("p", "Puck")
        val alarm = s.substringAfter("\"name\":\"set_alarm\"").substringBefore("\"name\":\"set_timer\"")
        val req = alarm.substringAfter("\"required\":[").substringBefore("]")
        assertFalse(req.contains("in_minutes")); assertFalse(req.contains("tomorrow")); assertFalse(req.contains("hour"))
        val msg = s.substringAfter("\"name\":\"send_message\"").substringBefore("\"name\":\"set_alarm\"")
        assertTrue(msg.substringAfter("\"required\":[").substringBefore("]").contains("body"))
    }
}
