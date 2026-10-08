package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoFloatingPointMoneyTest {
    private val rule = NoFloatingPointMoney(Config.empty)

    @Test
    fun `floating-point money properties and parameters are reported`() {
        val findings =
            rule.lint(
                """
                class Purchase(val price: Double, var paidTotal: Float?)
                val baseFee: kotlin.Double = 1.0
                val amountUsd = 1.5
                val refundAmount = -2f
                fun charge(feeAmount: Float, PRICE: Double) = Unit
                """.trimIndent(),
            )

        assertEquals(
            listOf("price", "paidTotal", "baseFee", "amountUsd", "refundAmount", "feeAmount", "PRICE"),
            findings.map { it.message.substringAfter("'").substringBefore("'") },
        )
        assertTrue(findings.first().message.contains("Money(micros: Long, currency)"))
    }

    @Test
    fun `money as Money or Long, and floating-point values with other names, are not reported`() {
        val findings =
            rule.lint(
                """
                data class Money(val micros: Long, val currency: String)
                class Purchase(val price: Money, val priceMicros: Long, val paid: List<Money>)
                val ratio: Double = 0.5
                val feeTier: Int = 1
                val amount = 3
                val volume = 0.8f
                val paidAt = 1L
                fun gain(progress: Float) = progress
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }
}
