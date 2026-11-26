package com.ultron.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeExtractTest {
    @Test fun stripsFencesAndChatter() {
        val raw = "Ye lo:\n```html\n<!DOCTYPE html><html><head><title>Meri Site</title></head><body>hi</body></html>\n```\nHo gaya."
        val c = CodeExtract.clean(raw)
        assertTrue(c.startsWith("<!DOCTYPE html>")); assertTrue(c.endsWith("</html>"))
        assertEquals("Meri Site", CodeExtract.titleOf(c, "x"))
    }
    @Test fun unfencedHtmlKept() = assertEquals("<html><body>a</body></html>", CodeExtract.clean("blah <html><body>a</body></html> bye"))
    @Test fun fileNameSafe() { assertEquals("meri-site.html", CodeExtract.fileName("Meri Site!!", "html")); assertEquals("ultron.html", CodeExtract.fileName("***", "html")) }
}
