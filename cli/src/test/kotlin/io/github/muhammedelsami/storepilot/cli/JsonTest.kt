package io.github.muhammedelsami.storepilot.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonTest {
    @Test
    fun `escapes strings`() {
        assertEquals(""""a\"b\\c\nd\te\u0001"""", Json.write("a\"b\\c\nd\te\u0001"))
    }

    @Test
    fun `writes nested values with indentation`() {
        assertEquals(
            """
            {
              "list": [
                1,
                2.5,
                true
              ],
              "empty": {}
            }
            """.trimIndent(),
            Json.write(linkedMapOf("list" to listOf(1, 2.5, true), "empty" to emptyMap<String, Any>())),
        )
    }
}
