package com.ultron.ai.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetEverythingTest {
    @Test fun failureDoesNotStopOtherStepsAndIsReported() = runBlocking {
        val ran = mutableListOf<String>()
        val r = ResetEverything.run(listOf(
            ResetEverything.Step("a") { ran += "a"; true },
            ResetEverything.Step("b") { ran += "b"; throw java.io.IOException("x") },
            ResetEverything.Step("c") { ran += "c"; false },
            ResetEverything.Step("d") { ran += "d"; true },
        ))
        assertEquals(listOf("a", "b", "c", "d"), ran)
        assertEquals(listOf(true, false, false, true), r.map { it.ok })
        val s = ResetEverything.summary(r)
        assertTrue(s.contains("b") && s.contains("c") && s.contains("adhura"))
    }
    @Test fun allOkSaysDone() = runBlocking {
        val r = ResetEverything.run(listOf(ResetEverything.Step("a") { true }))
        assertTrue(ResetEverything.summary(r).startsWith("Sab kuch reset ho gaya"))
    }
    @Test fun deleteRecursivelyRemovesFilesAndKeepsRoot() {
        val d = java.nio.file.Files.createTempDirectory("rst").toFile()
        File(d, "sub").mkdirs(); File(d, "sub/x.wav").writeText("1"); File(d, "y").writeText("2")
        assertTrue(ResetEverything.deleteRecursively(d))
        assertTrue(d.exists()); assertEquals(0, d.listFiles()!!.size)
        d.delete()
        assertTrue(ResetEverything.deleteRecursively(File(d, "missing")))
        assertFalse(File(d, "missing").exists())
    }
}
