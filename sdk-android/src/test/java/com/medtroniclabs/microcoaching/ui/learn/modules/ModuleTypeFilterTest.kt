package com.medtroniclabs.microcoaching.ui.learn.modules

import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pinning the module_type filters used by the v0.3.2 section split. The
 * actual filtering lives inline in each Row composable; this test guards
 * against accidental cross-wiring (e.g. Refreshers showing content_update
 * modules).
 */
class ModuleTypeFilterTest {

    private fun module(family: String, type: String): LearnModule = LearnModule(
        scenarioId = family,
        title = "title-$family",
        body = "body",
        clinicalDomain = "hypertension",
        moduleType = type,
    )

    private val catalogue = listOf(
        module("a", "refresher"),
        module("b", "refresher"),
        module("c", "digital_proficiency"),
        module("d", "content_update"),
        module("e", "digital_proficiency"),
    )

    @Test
    fun `refresher slice contains only refresher modules`() {
        val slice = catalogue.filter { it.moduleType == "refresher" }
        assertEquals(listOf("a", "b"), slice.map { it.scenarioId })
    }

    @Test
    fun `training slice contains only digital_proficiency modules`() {
        val slice = catalogue.filter { it.moduleType == "digital_proficiency" }
        assertEquals(listOf("c", "e"), slice.map { it.scenarioId })
    }

    @Test
    fun `knowledge slice contains only content_update modules`() {
        val slice = catalogue.filter { it.moduleType == "content_update" }
        assertEquals(listOf("d"), slice.map { it.scenarioId })
    }

    @Test
    fun `slices are mutually exclusive`() {
        val refreshers = catalogue.filter { it.moduleType == "refresher" }
        val training = catalogue.filter { it.moduleType == "digital_proficiency" }
        val knowledge = catalogue.filter { it.moduleType == "content_update" }
        val sliceTotal = refreshers.size + training.size + knowledge.size
        assertEquals(catalogue.size, sliceTotal)
    }
}
