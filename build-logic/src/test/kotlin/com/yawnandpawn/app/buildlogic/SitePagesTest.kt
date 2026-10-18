package com.yawnandpawn.app.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.7: `checkSitePages` rules, one test per row of the spec's I/O matrix, and the task on a fixture project. */
class SitePagesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val email = "help@example.com"
    private val base = "https://owner.github.io/project"
    private val emDash = Char(0x2014)

    private val config =
        """
        title: Fixture
        url: "https://owner.github.io"
        baseurl: "/project"
        """.trimIndent()

    private val appLinks =
        mapOf(
            "support.email" to email,
            "privacy.url" to "$base/privacy/",
            "terms.url" to "$base/terms/",
            "support.url" to "$base/support/",
            "play.orderHistory" to "https://play.google.com/store/account/orderhistory",
        )

    private val appStrings =
        mapOf(
            "payments_fee_body" to "Each snooze costs your base fee times the snooze number.",
            "payments_unused_body" to "Payments that weren't used are refunded automatically by Google.",
            "payments_cap_body" to "You can snooze up to your max snoozes per session. A single snooze never costs more than %1\$s.",
        )
    private val mirroredKeys =
        mapOf<String, List<String>>(
            "payments_fee_body" to emptyList(),
            "payments_cap_body" to listOf("$50"),
            "payments_unused_body" to emptyList(),
        )

    private fun page(
        permalink: String?,
        body: String,
    ): String = (if (permalink == null) "---\ntitle: Page\n---\n\n" else "---\ntitle: Page\npermalink: $permalink\n---\n\n") + body

    private val privacyBody: String =
        SitePagesRules.PRIVACY_HEADINGS.joinToString("\n\n") { "## $it\n\nText." } + "\n\nWrite to $email.\n"

    private val validFiles: Map<String, String> =
        mapOf(
            "_config.yml" to config,
            "index.md" to page("/", "# Home\n"),
            "privacy.md" to page("/privacy/", privacyBody),
            "terms.md" to
                page(
                    "/terms/",
                    "# Terms\n\n${SitePagesRules.Q17_START}\n{% comment %} TODO(Q17): write it {% endcomment %}\nPlaceholder.\n" +
                        "${SitePagesRules.Q17_END}\n",
                ),
            "support.md" to page("/support/", "# Support\n\nEmail <$email>.\n"),
            "payments.md" to
                page(
                    "/payments/",
                    "# Payments\n\nEach snooze costs your base fee\ntimes the snooze number.\n\n" +
                        "You can snooze up to your max snoozes per session. A single snooze never costs more than $50.\n\n" +
                        "Payments that weren't used are refunded automatically by Google.\n",
                ),
        )

    private fun violations(
        files: Map<String, String?> = validFiles,
        links: Map<String, String> = appLinks,
        strings: Map<String, String> = appStrings,
        keys: Map<String, List<String>> = mirroredKeys,
    ): List<String> = SitePagesRules.violations(SiteInput(files, links, strings, keys))

    private fun with(
        name: String,
        text: String?,
    ): Map<String, String?> = validFiles + (name to text)

    @Test
    fun `all good - five pages, config, links and mirrored strings pass, CRLF and a BOM too`() {
        assertEquals(emptyList(), violations())
        val windows = validFiles.mapValues { (_, text) -> Char(0xFEFF) + text.replace("\n", "\r\n") }
        assertEquals(emptyList(), violations(windows))
    }

    @Test
    fun `page missing - a missing page or config fails naming the file`() {
        assertEquals(listOf("support.md is missing"), violations(with("support.md", null)))
        assertEquals(listOf("_config.yml is missing"), violations(with("_config.yml", null)))
        assertEquals(listOf("support.md is missing"), violations(validFiles - "support.md"))
    }

    @Test
    fun `heading missing - privacy without a required heading fails naming it`() {
        val text = page("/privacy/", privacyBody.replace("## Backup\n", "## Backups\n"))

        assertEquals(listOf("privacy.md: missing heading '## Backup'"), violations(with("privacy.md", text)))
    }

    @Test
    fun `heading missing - privacy without the security or retention section fails naming it`() {
        listOf("Keeping your data safe", "How long data is kept").forEach { heading ->
            val text = page("/privacy/", privacyBody.replace("## $heading\n", "## Other\n"))

            assertEquals(listOf("privacy.md: missing heading '## $heading'"), violations(with("privacy.md", text)))
        }
    }

    @Test
    fun `TODO - outside the Q17 markers, an unpaired marker or a marker outside terms fails`() {
        val terms = validFiles.getValue("terms.md")
        assertEquals(
            listOf("index.md: TODO outside the marked Q17 placeholder"),
            violations(with("index.md", page("/", "# Home\n\nTODO: finish\n"))),
        )
        assertEquals(
            listOf("terms.md: TODO outside the marked Q17 placeholder"),
            violations(with("terms.md", "$terms\nTODO later\n")),
        )
        val unpaired = violations(with("terms.md", terms.replace(SitePagesRules.Q17_END, "")))
        assertTrue(unpaired.any { "needs exactly one" in it }, unpaired.toString())
        assertTrue(unpaired.any { "TODO outside" in it }, unpaired.toString())
        val reversed =
            terms
                .replace(SitePagesRules.Q17_START, "@@")
                .replace(SitePagesRules.Q17_END, SitePagesRules.Q17_START)
                .replace("@@", SitePagesRules.Q17_END)
        assertTrue(violations(with("terms.md", reversed)).any { "needs exactly one" in it })
        val elsewhere = page("/support/", "# Support\n\n$email\n${SitePagesRules.Q17_START}\n${SitePagesRules.Q17_END}\n")
        assertEquals(
            listOf("support.md: the Q17 placeholder markers belong only in terms.md"),
            violations(with("support.md", elsewhere)),
        )
    }

    @Test
    fun `copy rule - an em dash, a banned word or backup check fails naming page and rule`() {
        val support = validFiles.getValue("support.md")
        assertEquals(
            listOf("support.md: em dash (use a period, comma or '·')"),
            violations(with("support.md", "$support\nOne $emDash two.\n")),
        )
        assertEquals(
            listOf("support.md: banned word 'Seamless'"),
            violations(with("support.md", "$support\nSeamless setup.\n")),
        )
        assertEquals(
            listOf("support.md: say 'fallback check', never 'backup check'"),
            violations(with("support.md", "$support\nUse the backup check.\n")),
        )
        val fine = "$support\nThe elevator and the backup copy are fine. Prices are in USD, $1.\n"
        assertEquals(emptyList(), violations(with("support.md", fine)))
    }

    @Test
    fun `copy rule - every md file of the site is checked, not only the required pages`() {
        val site = folder.newFolder("site")
        validFiles.forEach { (name, text) -> File(site, name).writeText(text) }
        File(site, "faq.md").writeText(page("/faq/", "# FAQ\n\nOne $emDash two.\n"))
        File(site, "notes").mkdirs()
        File(site, "notes/old.md").writeText("Seamless TODO.\n")
        File(site, "notes/readme.txt").writeText("Seamless, not a page.\n")

        val files = SiteFiles.read(site)

        assertEquals(validFiles.keys + setOf("faq.md", "notes/old.md"), files.keys)
        assertEquals(
            listOf(
                "faq.md: em dash (use a period, comma or '·')",
                "notes/old.md: TODO outside the marked Q17 placeholder",
                "notes/old.md: banned word 'Seamless'",
            ),
            violations(files).sorted(),
        )
        val missing = SiteFiles.read(File(folder.root, "nothing-here"))
        assertEquals(SitePagesRules.REQUIRED_FILES.toSet(), missing.keys)
        assertTrue(missing.values.all { it == null })
    }

    @Test
    fun `link drift - a url that is not url + baseurl + permalink, or a page with no permalink, fails`() {
        assertEquals(
            listOf("app-links.properties: privacy.url is '$base/privacy.html', but privacy.md is published at '$base/privacy/'"),
            violations(links = appLinks + ("privacy.url" to "$base/privacy.html")),
        )
        assertEquals(
            listOf("terms.md: no permalink in its front matter"),
            violations(with("terms.md", page(null, "# Terms\n"))),
        )
        assertEquals(listOf("app-links.properties: terms.url is missing"), violations(links = appLinks - "terms.url"))
        assertEquals(
            listOf("app-links.properties: faq.url names no page (faq.md is not one of ${SitePagesRules.PAGES})"),
            violations(links = appLinks + ("faq.url" to "$base/faq/")),
        )
        assertEquals(
            listOf("_config.yml: no url"),
            violations(with("_config.yml", "title: Fixture\nbaseurl: /project\n")),
        )
    }

    @Test
    fun `email drift - the support address missing from support or privacy fails`() {
        assertEquals(
            listOf("support.md: does not show support.email '$email'"),
            violations(with("support.md", page("/support/", "# Support\n"))),
        )
        assertEquals(
            listOf("privacy.md", "support.md").map { "$it: does not show support.email 'other@example.com'" },
            violations(links = appLinks + ("support.email" to "other@example.com")).sorted(),
        )
        assertEquals(listOf("app-links.properties: support.email is missing"), violations(links = appLinks - "support.email"))
    }

    @Test
    fun `mirror drift - an in-app sentence not in payments fails naming the key`() {
        val changed = appStrings + ("payments_unused_body" to "Unused payments are refunded by Google.")

        val found = violations(strings = changed)

        assertEquals(1, found.size, found.toString())
        assertTrue(found.single().startsWith("payments.md: does not mirror 'payments_unused_body'"), found.toString())
        assertEquals(listOf("strings.xml: no string 'payments_fee_body' to mirror"), violations(strings = appStrings - "payments_fee_body"))
    }

    @Test
    fun `mirror placeholders - each placeholder must show its configured value, the words around it still must match`() {
        val payments = validFiles.getValue("payments.md")
        val capSentence = "A single snooze never costs more than $50."
        // Another currency than the configured "$50" fails.
        assertEquals(1, violations(with("payments.md", payments.replace("$50.", "50,00 €."))).size)
        // Another amount ("$5."), a page without the sentence, or other words around the value, fails naming the key.
        listOf(
            payments.replace("$50.", "$5."),
            payments.replace(capSentence, ""),
            payments.replace(capSentence, "One morning never costs more than $50."),
            payments.replace("$50.", "fifty dollars."),
        ).forEach { broken ->
            val found = violations(with("payments.md", broken))
            assertEquals(1, found.size, found.toString())
            assertTrue(found.single().startsWith("payments.md: does not mirror 'payments_cap_body'"), found.toString())
        }
        // Regex characters in the app's text are literal, and %2$d takes the second value.
        val counted = appStrings + ("payments_cap_body" to "Snooze (up to %2\$d) costs %1\$s.")
        val countedKeys = mirroredKeys + ("payments_cap_body" to listOf("$3", "5"))
        val page = with("payments.md", payments.replace(capSentence, "Snooze (up to 5) costs $3."))
        assertEquals(emptyList(), violations(page, strings = counted, keys = countedKeys))
        val loose = with("payments.md", payments.replace(capSentence, "Snooze up to 5 costs $3."))
        assertEquals(1, violations(loose, strings = counted, keys = countedKeys).size)
    }

    @Test
    fun `mirror placeholders - a placeholder with no configured value, or a value with no placeholder, fails naming the key`() {
        listOf(
            mirroredKeys + ("payments_cap_body" to emptyList()),
            mirroredKeys + ("payments_cap_body" to listOf("$50", "5")),
            mirroredKeys + ("payments_fee_body" to listOf("$1")),
        ).forEach { keys ->
            val found = violations(keys = keys)
            assertEquals(1, found.size, found.toString())
            assertTrue(found.single().startsWith("mirroredKeys: '"), found.toString())
            assertTrue(keys.any { (key, values) -> values != mirroredKeys[key] && "'$key'" in found.single() }, found.toString())
        }
    }

    @Test
    fun `front matter and yaml parsing - quotes and comments, and none without the dashes`() {
        assertEquals(
            mapOf("title" to "A & B", "permalink" to "/x/"),
            SiteText.frontMatter("---\ntitle: \"A & B\"\npermalink: /x/ # here\n---\nbody"),
        )
        assertEquals(emptyMap(), SiteText.frontMatter("# no front matter\npermalink: /x/\n"))
        assertEquals(
            mapOf("url" to "https://a.b", "baseurl" to ""),
            SiteText.yaml(listOf("url: 'https://a.b'", "baseurl: \"\"", "  nested: no")),
        )
    }

    @Test
    fun `yaml parsing - a quoted value with a trailing comment keeps only the text inside the quotes`() {
        assertEquals(
            mapOf(
                "url" to "https://a.b",
                "baseurl" to "/x",
                "title" to "A # B",
                "plain" to "value",
                "empty" to "",
            ),
            SiteText.yaml(
                listOf(
                    "url: \"https://a.b\" # the site",
                    "baseurl: '/x'   # the repo",
                    "title: \"A # B\"",
                    "plain: value # note",
                    "empty: # nothing",
                ),
            ),
        )
    }

    @Test
    fun `strings are read by key with Android escapes resolved, plurals and arrays ignored`() {
        val xml =
            """
            <resources>
                <string name="a">It\'s here &amp; there.</string>
                <string name="b">Line\nbreak</string>
                <plurals name="c"><item quantity="one">one</item></plurals>
            </resources>
            """.trimIndent()

        assertEquals(mapOf("a" to "It's here & there.", "b" to "Line break"), SiteText.strings(xml))
    }

    @Test
    fun `the task fails on a broken site naming every problem, and passes on a good one`() {
        val root = folder.root
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        File(root, "build.gradle.kts").writeText(
            """
            plugins { id("yawnandpawn.site-pages") }
            sitePages {
                siteDir.set(layout.projectDirectory.dir("site"))
                appLinks.set(layout.projectDirectory.file("app-links.properties"))
                appStrings.set(layout.projectDirectory.file("strings.xml"))
                mirroredKeys.set(
                    mapOf(
                        "payments_fee_body" to listOf<String>(),
                        "payments_cap_body" to listOf("${'$'}50"),
                        "payments_unused_body" to listOf<String>(),
                    ),
                )
            }
            """.trimIndent(),
        )
        File(root, "app-links.properties").writeText(appLinks.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        File(root, "strings.xml").writeText(
            "<resources>\n" +
                appStrings.entries.joinToString("\n") { "<string name=\"${it.key}\">${it.value.replace("'", "\\'")}</string>" } +
                "\n</resources>\n",
        )
        val site = File(root, "site").apply { mkdirs() }
        validFiles.forEach { (name, text) -> File(site, name).writeText(text) }
        File(site, "support.md").delete()
        File(site, "index.md").appendText("TODO\n")
        val runner =
            GradleRunner
                .create()
                .withProjectDir(root)
                .withPluginClasspath()
                .withArguments(CHECK_SITE_PAGES)

        val failed = runner.buildAndFail()
        assertTrue("support.md is missing" in failed.output, failed.output)
        assertTrue("index.md: TODO outside the marked Q17 placeholder" in failed.output, failed.output)

        validFiles.forEach { (name, text) -> File(site, name).writeText(text) }
        val passed = runner.build()
        assertEquals(TaskOutcome.SUCCESS, passed.task(":$CHECK_SITE_PAGES")?.outcome)
        assertTrue(File(root, "build/checkSitePages/ok.txt").isFile)
    }
}
