package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.TaxNote
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 4.13: `config/tax-exclusive-countries.txt` is the list the confirm sheet's tax note follows. */
class TaxExclusiveCountriesFileTest {
    private val fileCountries: Set<String> =
        File(checkNotNull(System.getProperty("yawnandpawn.taxExclusiveCountries")) { "yawnandpawn.taxExclusiveCountries not set" })
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    @Test
    fun `the shared file lists exactly the countries TaxNote uses, initially US and CA`() {
        assertEquals(TaxNote.TAX_EXCLUSIVE_COUNTRIES, fileCountries)
        assertEquals(setOf("US", "CA"), fileCountries)
    }

    @Test
    fun `the note shows for a listed billing country in any case, and not for others or an unknown one`() {
        assertTrue(TaxNote.shows("US"))
        assertTrue(TaxNote.shows("ca"))
        assertFalse(TaxNote.shows("FI"))
        assertFalse(TaxNote.shows(""))
        assertFalse(TaxNote.shows(null))
    }
}
