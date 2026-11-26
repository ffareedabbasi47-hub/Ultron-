package com.ultron.ai.domain.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilesTest {
    @Test fun safePathAcceptsNormalAndRefusesTraversal() {
        assertEquals("index.html", ProjectFiles.safePath("index.html"))
        assertEquals("css/style.css", ProjectFiles.safePath("./css/style.css"))
        for (bad in listOf("../x.html", "/etc/passwd.txt", "a/../b.html", ".env.txt", "a//b.html", "x.exe", "noext", "a/b/c/d.html", "C:/x.html", "con trol.html", ""))
            assertNull(bad, ProjectFiles.safePath(bad))
        assertNull(ProjectFiles.safePath("x".repeat(90) + ".html"))
    }

    @Test fun parsesMultipleFilesAndStripsFences() {
        val raw = "=== FILE: index.html ===\n```html\n<!doctype html><html><body>hi</body></html>\n```\n=== FILE: css/style.css ===\nbody{margin:0}\n=== END ==="
        val f = ProjectFiles.parse(raw)
        assertEquals(listOf("index.html", "css/style.css"), f.map { it.path })
        assertTrue(f[0].content.startsWith("<!doctype"))
        assertEquals("body{margin:0}", f[1].content)
    }

    @Test fun unsafeBlocksAreSkippedAndDuplicatesIgnored() {
        val raw = "=== FILE: ../evil.html ===\nx\n=== FILE: a.js ===\n1\n=== FILE: a.js ===\n2\n"
        val f = ProjectFiles.parse(raw)
        assertEquals(1, f.size); assertEquals("1", f[0].content)
    }

    @Test fun fallsBackToSingleHtmlReply() {
        val f = ProjectFiles.parse("Here you go:\n```html\n<!doctype html><html><body>x</body></html>\n```")
        assertEquals(listOf("index.html"), f.map { it.path })
    }

    @Test fun capsFileCountAndRefusesGarbage() {
        val many = (1..20).joinToString("\n") { "=== FILE: f$it.txt ===\nx" }
        assertEquals(ProjectFiles.MAX_FILES, ProjectFiles.parse(many).size)
        assertTrue(ProjectFiles.parse("sorry I cannot").isEmpty())
    }

    @Test fun promptsCarrySkillsAndPlan() {
        val p = BuilderPrompts.buildSystem("UI-RULES", "ARCH-RULES", "THE-PLAN")
        assertTrue("UI-RULES" in p && "ARCH-RULES" in p && "THE-PLAN" in p && "=== FILE:" in p)
    }

    @Test fun folderNameIsSafe() {
        assertEquals("meri-dukaan-site-20260101", ProjectFiles.folderName("Meri Dukaan Site!!", "20260101"))
        assertEquals("project-1", ProjectFiles.folderName("???", "1"))
    }
}
