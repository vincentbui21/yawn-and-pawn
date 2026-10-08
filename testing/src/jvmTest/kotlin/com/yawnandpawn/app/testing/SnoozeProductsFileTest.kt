package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.FeeStep
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.error.Outcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** `config/snooze-products.txt` is the one list `tools/play-catalog` creates on Play; the ladder must stay inside it. */
class SnoozeProductsFileTest {
    private val fileIds: List<String> =
        File(checkNotNull(System.getProperty("yawnandpawn.snoozeProducts")) { "yawnandpawn.snoozeProducts not set" })
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `the shared product file lists exactly the 50 catalogue ids in order`() {
        assertEquals(SnoozeProducts.all, fileIds)
    }

    @Test
    fun `every product the ladder returns for a reachable combination is in the shared file`() {
        val returned =
            FeeRules.BASE_FEE_TIERS
                .flatMap { b -> FeeRules.MAX_SNOOZES.map { n -> UsdFeeLadder.productFor(b, n) } }
                .map { ((it as Outcome.Success).value as FeeStep.Product).productId }
                .toSet()
        assertEquals(28, returned.size)
        assertEquals(emptySet(), returned - fileIds.toSet())
    }
}
