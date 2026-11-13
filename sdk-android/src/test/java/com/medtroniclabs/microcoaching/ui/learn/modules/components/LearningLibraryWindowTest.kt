package com.medtroniclabs.microcoaching.ui.learn.modules.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the SK Learning Library collapse rule (LEAP-133): 5 first, then "Show all". */
class LearningLibraryWindowTest {

    @Test
    fun `more than five collapsed shows five and the Show all button`() {
        assertEquals(LearningLibraryWindow(visibleCount = 5, showAllButton = true), learningLibraryWindow(43, expanded = false))
    }

    @Test
    fun `expanded shows everything and hides the button`() {
        assertEquals(LearningLibraryWindow(visibleCount = 43, showAllButton = false), learningLibraryWindow(43, expanded = true))
    }

    @Test
    fun `exactly five needs no button`() {
        assertEquals(LearningLibraryWindow(visibleCount = 5, showAllButton = false), learningLibraryWindow(5, expanded = false))
    }

    @Test
    fun `six is the first count that collapses`() {
        assertEquals(LearningLibraryWindow(visibleCount = 5, showAllButton = true), learningLibraryWindow(6, expanded = false))
    }

    @Test
    fun `fewer than five shows them all`() {
        assertEquals(LearningLibraryWindow(visibleCount = 2, showAllButton = false), learningLibraryWindow(2, expanded = false))
    }

    @Test
    fun `empty shows nothing`() {
        assertEquals(LearningLibraryWindow(visibleCount = 0, showAllButton = false), learningLibraryWindow(0, expanded = false))
    }
}
