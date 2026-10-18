package com.yawnandpawn.app.core.reliability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.5: the maker of a phone from `Build.MANUFACTURER` and `Build.BRAND`, and which makers the guidance covers. */
class DeviceMakerTest {
    private val aliases: Map<String, DeviceMaker> =
        mapOf(
            "xiaomi" to DeviceMaker.Xiaomi,
            "redmi" to DeviceMaker.Xiaomi,
            "poco" to DeviceMaker.Xiaomi,
            "samsung" to DeviceMaker.Samsung,
            "huawei" to DeviceMaker.Huawei,
            "honor" to DeviceMaker.Huawei,
            "oppo" to DeviceMaker.OppoRealme,
            "realme" to DeviceMaker.OppoRealme,
            "vivo" to DeviceMaker.Vivo,
            "iqoo" to DeviceMaker.Vivo,
            "oneplus" to DeviceMaker.OnePlus,
        )

    @Test
    fun `every alias maps in any case, trimmed, as the manufacturer or as the brand`() {
        aliases.forEach { (alias, maker) ->
            listOf(alias, alias.uppercase(), alias.replaceFirstChar { it.uppercase() }, "  $alias \t").forEach { name ->
                assertEquals(maker, DeviceMaker.of(name, ""), "manufacturer '$name'")
                assertEquals(maker, DeviceMaker.of("unknown", name), "brand '$name'")
            }
        }
        assertEquals(DeviceMaker.OnePlus, DeviceMaker.of("OnePlus"))
        assertEquals(DeviceMaker.OppoRealme, DeviceMaker.of("realme", "realme"))
    }

    @Test
    fun `the manufacturer wins over the brand`() {
        assertEquals(DeviceMaker.Xiaomi, DeviceMaker.of("Xiaomi", "Redmi"))
        assertEquals(DeviceMaker.OppoRealme, DeviceMaker.of("OPPO", "OnePlus"))
        assertEquals(DeviceMaker.Samsung, DeviceMaker.of("samsung", "google"))
    }

    @Test
    fun `anything else is Other, which the guidance does not cover`() {
        listOf("Google" to "google", "motorola" to "motorola", "" to "", "Xiaomi Inc" to "mi", "realme2" to "huaweii").forEach { (m, b) ->
            assertEquals(DeviceMaker.Other, DeviceMaker.of(m, b), "'$m' / '$b'")
        }
        assertFalse(DeviceMaker.Other.covered)
        DeviceMaker.entries.filter { it != DeviceMaker.Other }.forEach { assertTrue(it.covered, "$it") }
    }

    @Test
    fun `only a covered maker shows the Manufacturer row`() {
        val device = DeviceInfo(sdkInt = 34, maker = "Google", fingerprint = "build-1")
        assertFalse(device.makerCovered)
        val other = ReliabilityChecklist.evaluate(ReliabilityStatus.ALL_OK, emptyList(), device, StoredReliability())
        assertEquals(RowStatus.Hidden, other.single { it.row == ChecklistRow.Manufacturer }.status)

        val samsung = device.copy(maker = "samsung", deviceMaker = DeviceMaker.of("samsung"))
        assertTrue(samsung.makerCovered)
        val covered = ReliabilityChecklist.evaluate(ReliabilityStatus.ALL_OK, emptyList(), samsung, StoredReliability())
        assertEquals(RowStatus.NeedsFix, covered.single { it.row == ChecklistRow.Manufacturer }.status)
    }
}
