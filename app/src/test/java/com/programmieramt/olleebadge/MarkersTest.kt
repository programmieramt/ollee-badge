package com.programmieramt.olleebadge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkersTest {

    private val rules = Markers.parse(
        """
        Alex = A*
        Sam=S*
          family =  f*
        broken line
        = X
        Empty =
        Long = ABC
        """.trimIndent()
    )

    @Test
    fun parsesValidLinesOnly() {
        assertEquals(listOf("Alex", "Sam", "family", "Long"), rules.map { it.name })
        assertEquals(listOf("A*", "S*", "f*", "AB"), rules.map { it.cell })
    }

    @Test
    fun matchesWholeNameIgnoringCase() {
        assertEquals("A*", Markers.cellFor(rules, "Alex"))
        assertEquals("A*", Markers.cellFor(rules, "alex"))
        assertEquals("f*", Markers.cellFor(rules, "Family"))
        assertEquals("S*", Markers.cellFor(rules, "  Sam "))
    }

    @Test
    fun matchesNameFollowedByMoreWords() {
        assertEquals("A*", Markers.cellFor(rules, "Alex Smith"))
    }

    @Test
    fun ignoresOtherChats() {
        assertNull(Markers.cellFor(rules, "Alexander"))
        assertNull(Markers.cellFor(rules, "Samstag"))
        assertNull(Markers.cellFor(rules, "Work"))
        assertNull(Markers.cellFor(rules, ""))
        assertNull(Markers.cellFor(rules, null))
        assertNull(Markers.cellFor(emptyList(), "Alex"))
    }
}
