package org.example

import kotlin.test.Test
import kotlin.test.assertEquals

class RunSupportTest {

    @Test
    fun `formatIdRanges collapses consecutive ids into the ranges CASE_IDS accepts`() {
        assertEquals("3-5,9", formatIdRanges(listOf(9, 3, 4, 5)))
        assertEquals("1-2,4-6,40-43", formatIdRanges(listOf(1, 2, 4, 5, 6, 40, 41, 42, 43)))
        assertEquals("0", formatIdRanges(listOf(0)))
        assertEquals("", formatIdRanges(emptyList()))
    }
}
