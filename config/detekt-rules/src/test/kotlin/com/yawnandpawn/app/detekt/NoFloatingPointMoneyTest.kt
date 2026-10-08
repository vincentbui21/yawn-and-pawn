package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoFloatingPointMoneyTest {
    private val rule = NoFloatingPointMoney(Config.empty)

    private fun reported(code: String): List<String> = rule.lint(code).map { it.message.substringAfter("'").substringBefore("'") }

    @Test
    fun `floating-point money properties and parameters are reported`() {
        assertEquals(
            listOf("price", "paidTotal", "baseFee", "amountUsd", "refundAmount", "feeAmount", "PRICE", "basePrice"),
            reported(
                """
                class Purchase(val price: Double, var paidTotal: Float?)
                val baseFee: kotlin.Double = 1.0
                val amountUsd = 1.5
                val refundAmount = -2f
                fun charge(feeAmount: Float, PRICE: Double) = Unit
                val basePrice: Double = 0.0
                """.trimIndent(),
            ),
        )
        assertTrue(
            rule
                .lint("val price: Double = 1.0")
                .single()
                .message
                .contains("Money(micros: Long, currency)"),
        )
    }

    @Test
    fun `floating-point money without a literal type is reported too`() {
        assertEquals(
            listOf("feeTotal", "price", "amount", "price", "paidAmounts", "fees", "price_usd", "totalPaid"),
            reported(
                """
                fun f(n: Int, cents: Long, micros: Long) {
                    val feeTotal = 1.5 * n
                    val price = cents / 100.0
                    val amount = micros.toDouble()
                }
                fun price(): Double = 1.0
                val paidAmounts: List<Double> = emptyList()
                val fees: DoubleArray = DoubleArray(2)
                val price_usd = 3.toFloat()
                fun totalPaid(micros: Long) = micros / 1e6
                """.trimIndent(),
            ),
        )
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
                val feedbackGain: Float = 0.5f
                val feet = 1.5
                val paramount = 2.0
                val prepaidCredit = 1.0
                fun gain(progress: Float) = progress
                fun priceOf(micros: Long): Money = Money(micros, "USD")
                fun amountText(): String { return 1.5.toString() }
                """.trimIndent(),
            )

        assertEquals(emptyList(), findings.map { it.message })
    }

    @Test
    fun `identifiers split into camelCase and snake_case words`() {
        assertEquals(listOf("base", "price", "usd"), NoFloatingPointMoney.words("basePriceUSD"))
        assertEquals(listOf("paid", "total"), NoFloatingPointMoney.words("PAID_TOTAL"))
        assertEquals(listOf("feedback", "gain"), NoFloatingPointMoney.words("feedbackGain"))
        assertEquals(listOf("price"), NoFloatingPointMoney.words("PRICE"))
    }
}
