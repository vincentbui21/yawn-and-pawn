package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoUnseededRandomTest {
    private val rule = NoUnseededRandom(Config.empty)

    private fun findings(
        code: String,
        packageName: String = "com.yawnandpawn.app.core.checks.math",
    ) = rule.lint("package $packageName\n\n$code\n")

    @Test
    fun `an unseeded draw in a check is reported pointing to SeedDeriver`() {
        val findings = findings("fun pick() = kotlin.random.Random.nextInt(10)")

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("SeedDeriver"), findings.single().message)
    }

    @Test
    fun `every unseeded form is reported once in core checks and core session`() {
        val violations =
            listOf(
                "fun a() = Random.nextInt(10)",
                "fun a() = Random.nextLong()",
                "fun a() = kotlin.random.Random.nextBoolean()",
                "val r = Random.Default",
                "fun a() = Random.Default.nextInt()",
                "fun a() = java.util.Random()",
                "fun a() = Random()",
                "fun a() = java.security.SecureRandom()",
                "fun a() = ThreadLocalRandom.current().nextInt()",
                "fun a() = (1..10).random()",
                "fun a() = listOf(1, 2).randomOrNull()",
                "fun a() = listOf(1, 2).shuffled()",
                "fun a() = mutableListOf(1, 2).shuffle()",
                "fun a() = Math.random()",
                "fun a() = kotlin.uuid.Uuid.random()",
                "import kotlin.random.Random.Default.nextInt\nfun a() = 1",
                "import kotlin.random.Random.Default\nfun a() = 1",
                "import java.util.Random\nfun a() = 1",
                "import java.security.SecureRandom\nfun a() = 1",
                "import java.util.concurrent.ThreadLocalRandom\nfun a() = 1",
                "import kotlin.random.Random as R\nfun a() = 1",
                "fun a() = listOf(1, 2).shuffled(Random)",
                "fun a() = (1..10).random(kotlin.random.Random)",
                "fun a() = listOf(1, 2).shuffled(random = Random)",
                "fun a() = listOf(1, 2).shuffled(Random.Default)",
                "fun a() = listOf(1, 2).shuffled(Random.Companion)",
                "fun a() = Random::nextInt",
                "fun a() = kotlin.random.Random::nextLong",
                "fun a() = ThreadLocalRandom::current",
                "fun a() = java.util.UUID.randomUUID()",
                "fun a() = UUID.randomUUID().toString()",
                "fun a(list: MutableList<Int>) = java.util.Collections.shuffle(list)",
                "fun a(list: MutableList<Int>) = Collections.shuffle(list)",
            )
        val packages = listOf("com.yawnandpawn.app.core.checks", "com.yawnandpawn.app.core.checks.math", "com.yawnandpawn.app.core.session")
        packages.forEach { pkg ->
            violations.forEach { code -> assertEquals(1, findings(code, pkg).size, "$code in $pkg") }
        }
    }

    @Test
    fun `a seeded generator and calls given one are allowed`() {
        val allowed =
            listOf(
                "fun a(seed: Long) = kotlin.random.Random(seed).nextInt(10)",
                "fun a(seed: Long) = Random(seed).nextInt()",
                "fun a(r: kotlin.random.Random) = (1..10).random(r)",
                "fun a(r: kotlin.random.Random) = listOf(1, 2).shuffled(r)",
                "fun a(r: SeededRandom) = r.nextInt(1..9)",
                "import kotlin.random.Random\nfun a(seed: Long) = Random(seed)",
                "class SeededRandom { fun nextInt(range: IntRange) = range.first }",
                "fun a(list: MutableList<Int>, seed: Long) = java.util.Collections.shuffle(list, java.util.Random(seed))",
                "fun a(seed: Long) = listOf(1, 2).shuffled(Random(seed))",
                "fun a(r: SeededRandom) = listOf(1, 2).map(r::nextBoolean)",
                "fun a(list: MutableList<Int>) = list.shuffle(SeededRandom(1).asKotlin())",
                "fun a(id: String) = UUID.fromString(id)",
                "fun a() = CheckPlan(CheckMode.Random, emptyList())",
                "fun a() = listOf(CheckMode.Random).map(CheckMode.Random::equals)",
            )
        allowed.forEach { code -> assertEquals(0, findings(code).size, code) }
    }

    @Test
    fun `other packages are not checked`() {
        val others =
            listOf("com.yawnandpawn.app.core.id", "com.yawnandpawn.app.core", "com.yawnandpawn.app.core.checksum", "com.yawnandpawn.app.ui")
        others.forEach { pkg ->
            assertEquals(0, findings("fun a() = Random.nextInt(10)", pkg).size, pkg)
            assertEquals(0, findings("fun a() = kotlin.uuid.Uuid.random()", pkg).size, pkg)
        }
    }
}
