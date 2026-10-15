package com.supermetroid.editor.asm

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmSourceIncludesTest {
    @TempDir lateinit var directory: File
    private fun index(): AsmReferenceIndex {
        val src = File(directory, "src").apply { mkdirs() }
        for (id in listOf("main.asm", "bank_82.asm", "nested/room.asm", "nested/bank_82.asm", "nested/my bank.asm", "project/custom.asm"))
            File(src, id).apply { parentFile.mkdirs(); writeText("; $id\n") }
        return AsmSourceParser().parse(src, File(directory, "data").apply { mkdirs() }, emptyList())
    }

    @Test fun `includes resolve relative to the containing file without ambiguous basename fallback`() {
        val index = index()
        assertEquals("bank_82.asm", index.resolveSourceInclude("main.asm", "bank_82.asm")?.id)
        assertEquals("nested/bank_82.asm", index.resolveSourceInclude("nested/room.asm", "bank_82.asm")?.id)
        assertEquals("bank_82.asm", index.resolveSourceInclude("nested/room.asm", ".././bank_82.asm")?.id)
        assertEquals("nested/my bank.asm", index.resolveSourceInclude("main.asm", "nested\\my bank.asm")?.id)
        assertEquals("project/custom.asm", index.resolveSourceInclude("main.asm", "project/custom.asm")?.id)
        assertEquals("bank_82.asm", index.resolveSourceInclude("project/custom.asm", "../bank_82.asm")?.id)
        for (path in listOf("missing/bank_82.asm", "../../bank_82.asm", "/bank_82.asm", "C:\\bank_82.asm", "!dynamic.asm"))
            assertNull(index.resolveSourceInclude("main.asm", path), path)
    }

    @Test fun `live quoted bare and CRLF operands are navigable at every path character but not in comments`() {
        for (line in listOf("incsrc bank_82.asm", "  INCSRC \"nested/my bank.asm\" ; note", "incsrc \"nested/semi;colon.asm\"\r")) {
            val include = assertNotNull(asmSourceInclude(line))
            val text = "; another page\r\n$line\n"
            val start = text.indexOf(line)
            for (offset in include.range.first..include.range.last + 1)
                assertEquals(include, asmSourceIncludeAt(text, start + offset))
            assertNull(asmSourceIncludeAt(text, start))
            if (line.contains("; note")) assertNull(asmSourceIncludeAt(text, text.indexOf("note")))
        }
    }

    @Test fun `comments binary includes expressions and unterminated strings are never guessed as source files`() {
        for (line in listOf("; incsrc bank_82.asm", "incbin bank_82.asm", "incsrc \"bank_82.asm", "db \"incsrc bank_82.asm\"", "incsrc \"bank_82.asm\" + !suffix"))
            assertNull(asmSourceInclude(line), line)
        assertNull(asmSourceIncludeAt("", 0))
        assertNull(asmSourceIncludeAt("incsrc bank_82.asm", -1))
        assertNull(asmSourceIncludeAt("incsrc bank_82.asm", 500))
    }
}
