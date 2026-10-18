package com.yawnandpawn.app.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

const val CHECK_SITE_PAGES = "checkSitePages"

/**
 * What [SitePagesRules] checks: the texts of the published site and the app's links and strings.
 *
 * @property files every file of [SitePagesRules.REQUIRED_FILES] by name (null when the file is missing), plus every other
 *   `*.md` file of the site by its path relative to the site folder ([SiteFiles.read]).
 * @property appLinks the keys of `config/app-links.properties`.
 * @property appStrings the app's Compose strings by key (`strings.xml`).
 * @property mirroredKeys the keys whose text `payments.md` must contain word for word.
 */
data class SiteInput(
    val files: Map<String, String?>,
    val appLinks: Map<String, String>,
    val appStrings: Map<String, String>,
    val mirroredKeys: List<String>,
)

/**
 * Story 5.7 (FR-SET-3): the GitHub Pages site in `docs/site/` is complete, follows the copy rules and agrees with the
 * app. Pure rules; the task only reads the files.
 *
 * It fails when a page or `_config.yml` is missing, `privacy.md` lacks a required heading, "TODO" appears outside the
 * marked Q17 placeholder in `terms.md` (or a marker is unpaired or elsewhere), any `*.md` page breaks a copy rule (em dash, banned
 * hype words, "backup check"), a `*.url` link is not the config's `url` + `baseurl` + the page's permalink, a page has no
 * permalink, the support address is missing from `support.md` or `privacy.md`, or `payments.md` lacks an in-app
 * sentence it mirrors. The currency rule of the app's CopyRules is not applied: the terms state USD tiers.
 */
object SitePagesRules {
    const val CONFIG = "_config.yml"
    val PAGES: List<String> = listOf("index.md", "privacy.md", "terms.md", "support.md", "payments.md")
    val REQUIRED_FILES: List<String> = listOf(CONFIG) + PAGES
    val PRIVACY_HEADINGS: List<String> =
        listOf(
            "Data on your phone",
            "Backup",
            "Crash reports",
            "Usage stats",
            "Payments",
            "Camera and microphone",
            "Keeping your data safe",
            "How long data is kept",
            "Deleting your data",
            "Contact",
        )

    /** The app-links keys that must exist; every `*.url` key is also checked against its page. */
    val REQUIRED_LINK_KEYS: List<String> = listOf("privacy.url", "terms.url", "support.url", SUPPORT_EMAIL)

    /** The pages that must show the support address. */
    val EMAIL_PAGES: List<String> = listOf("support.md", "privacy.md")
    const val SUPPORT_EMAIL = "support.email"

    /** Liquid comments, so the published page never shows them (an HTML comment would stay in the page source). */
    const val Q17_START = "{% comment %} Q17-PLACEHOLDER-START {% endcomment %}"
    const val Q17_END = "{% comment %} Q17-PLACEHOLDER-END {% endcomment %}"
    private const val Q17_PAGE = "terms.md"
    private const val URL_SUFFIX = ".url"
    private const val EM_DASH_CODE = 0x2014
    private val EM_DASH = Char(EM_DASH_CODE)

    /** The same banned words and inflections as the app's CopyRules ("elevator" is fine). */
    private val bannedWords =
        Regex(
            """\b(elevat(e|es|ed|ing)|seamless(ly)?|unleash(es|ed|ing)?|supercharg(e|es|ed|er|ing))\b""",
            RegexOption.IGNORE_CASE,
        )

    /** "backup", "back-up" or "back up" next to "check(s)", in either order (the app's CopyRules). */
    private val backupCheck = Regex("""\bback[\s-]?up[\s-]+checks?\b|\bchecks?[\s-]+back[\s-]?up\b""", RegexOption.IGNORE_CASE)
    private val todo = Regex("""\bTODO\b""")
    private val whitespace = Regex("""\s+""")

    /** An Android format placeholder for a string or a number in the app's text: `%1$s`, `%2$d`. */
    private val placeholder = Regex("""%\d+\$[sd]""")

    /** Every problem of [input]; empty when the site passes. */
    fun violations(input: SiteInput): List<String> {
        val texts = input.files.mapValues { (_, text) -> text?.let(SiteText::normalized) }
        val missing = REQUIRED_FILES.filter { texts[it] == null }.map { "$it is missing" }
        val present = texts.filterValues { it != null }.mapValues { it.value.orEmpty() }
        return missing +
            headingViolations(present["privacy.md"]) +
            present.flatMap { (name, text) -> todoViolations(name, text) + copyViolations(name, text) } +
            linkViolations(present, input.appLinks) +
            emailViolations(present, input.appLinks) +
            mirrorViolations(present["payments.md"], input.appStrings, input.mirroredKeys)
    }

    /** The `## heading` lines `privacy.md` lacks. */
    fun headingViolations(privacy: String?): List<String> {
        if (privacy == null) return emptyList()
        val headings = privacy.lines().map { it.trim() }.toSet()
        return PRIVACY_HEADINGS.filter { "## $it" !in headings }.map { "privacy.md: missing heading '## $it'" }
    }

    /** "TODO" outside the Q17 markers, a marker without its pair, or a marker outside `terms.md`. */
    fun todoViolations(
        name: String,
        text: String,
    ): List<String> {
        val starts = text.occurrences(Q17_START)
        val ends = text.occurrences(Q17_END)
        val markers =
            when {
                starts == 0 && ends == 0 -> {
                    emptyList()
                }

                name != Q17_PAGE -> {
                    listOf("$name: the Q17 placeholder markers belong only in $Q17_PAGE")
                }

                starts != 1 || ends != 1 || text.indexOf(Q17_START) > text.indexOf(Q17_END) -> {
                    listOf("$name: needs exactly one $Q17_START followed by one $Q17_END")
                }

                else -> {
                    emptyList()
                }
            }
        val outside =
            if (name == Q17_PAGE && markers.isEmpty() && starts == 1) {
                text.substringBefore(Q17_START) + text.substringAfter(Q17_END)
            } else {
                text
            }
        val todos = if (todo.containsMatchIn(outside)) listOf("$name: TODO outside the marked Q17 placeholder") else emptyList()
        return markers + todos
    }

    /** The copy rules every published text follows: no em dash, no banned hype words, never "backup check". */
    fun copyViolations(
        name: String,
        text: String,
    ): List<String> =
        buildList {
            if (EM_DASH in text) add("$name: em dash (use a period, comma or '·')")
            bannedWords.find(text)?.let { add("$name: banned word '${it.value}'") }
            if (backupCheck.containsMatchIn(text)) add("$name: say 'fallback check', never 'backup check'")
        }

    /** Each page's permalink, and each `*.url` link equal to the config's `url` + `baseurl` + its page's permalink. */
    fun linkViolations(
        files: Map<String, String>,
        appLinks: Map<String, String>,
    ): List<String> {
        val permalinks = PAGES.filter { it in files }.associateWith { SiteText.frontMatter(files.getValue(it))["permalink"] }
        val noPermalink = permalinks.filterValues { it.isNullOrEmpty() }.keys.map { "$it: no permalink in its front matter" }
        val config = files[CONFIG]?.let { SiteText.yaml(it.lines()) }
        val keys = REQUIRED_LINK_KEYS.filter { it.endsWith(URL_SUFFIX) } + appLinks.keys.filter { it.endsWith(URL_SUFFIX) }
        val links =
            keys.distinct().mapNotNull { key ->
                val page = key.removeSuffix(URL_SUFFIX) + ".md"
                val actual = appLinks[key]
                val permalink = permalinks[page]
                when {
                    actual.isNullOrEmpty() -> {
                        "app-links.properties: $key is missing"
                    }

                    page !in PAGES -> {
                        "app-links.properties: $key names no page ($page is not one of $PAGES)"
                    }

                    config == null || permalink.isNullOrEmpty() -> {
                        null
                    }

                    config["url"].isNullOrEmpty() -> {
                        "$CONFIG: no url"
                    }

                    else -> {
                        val expected = config.getValue("url").trimEnd('/') + config["baseurl"].orEmpty().trimEnd('/') + permalink
                        if (actual != expected) "app-links.properties: $key is '$actual', but $page is published at '$expected'" else null
                    }
                }
            }
        return noPermalink + links.distinct()
    }

    /** The support address on the pages that must show it. */
    fun emailViolations(
        files: Map<String, String>,
        appLinks: Map<String, String>,
    ): List<String> {
        val email = appLinks[SUPPORT_EMAIL]
        if (email.isNullOrEmpty()) return listOf("app-links.properties: $SUPPORT_EMAIL is missing")
        return EMAIL_PAGES.filter { it in files && email !in files.getValue(it) }.map { "$it: does not show $SUPPORT_EMAIL '$email'" }
    }

    /**
     * Each mirrored in-app string in `payments.md`, word for word after normalising whitespace. A `%N$s` or `%N$d`
     * placeholder in the app's text (a value the app fills in, such as the formatted cap) matches any text without
     * spaces on the page (Story 5.8).
     */
    fun mirrorViolations(
        payments: String?,
        appStrings: Map<String, String>,
        mirroredKeys: List<String>,
    ): List<String> {
        if (payments == null) return emptyList()
        val page = payments.collapsed()
        return mirroredKeys.mapNotNull { key ->
            val text = appStrings[key]
            when {
                text == null -> "strings.xml: no string '$key' to mirror"
                !mirrorPattern(text.collapsed()).containsMatchIn(page) -> "payments.md: does not mirror '$key' (\"${text.collapsed()}\")"
                else -> null
            }
        }
    }

    /** [text] as a pattern: its words literally, each placeholder as one run of non-space characters. */
    private fun mirrorPattern(text: String): Regex = Regex(text.split(placeholder).joinToString("""\S+""") { Regex.escape(it) })

    private fun String.collapsed(): String = trim().replace(whitespace, " ")

    private fun String.occurrences(marker: String): Int = split(marker).size - 1
}

/** Parsing of the site's simple YAML and the app's strings file, for [SitePagesRules]. */
object SiteText {
    private const val BOM_CODE = 0xFEFF
    private val BYTE_ORDER_MARK = Char(BOM_CODE).toString()
    private val yamlPair = Regex("""^([A-Za-z_][\w-]*):\s*(.*?)\s*$""")

    /** The `key: value` pairs of a page's front matter (between the leading `---` lines); empty when it has none. */
    fun frontMatter(text: String): Map<String, String> {
        val lines = normalized(text).lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end < 0) emptyMap() else yaml(lines.subList(1, end + 1))
    }

    /** Top-level `key: value` pairs of simple YAML lines: quotes removed, a trailing ` # comment` dropped. */
    fun yaml(lines: List<String>): Map<String, String> =
        lines
            .mapNotNull { yamlPair.matchEntire(it) }
            .associate { match -> match.groupValues[1] to unquote(match.groupValues[2]) }

    /** The `<string>` texts of a Compose resources strings file, by key, with Android's simple escapes resolved. */
    fun strings(xml: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val elements = document.documentElement.childNodes
        return (0 until elements.length)
            .map { elements.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "string" }
            .associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    /**
     * A quoted value without its quotes (what follows the closing quote, such as ` # comment`, is dropped), else the
     * value before a ` #` comment.
     */
    private fun unquote(value: String): String {
        val quote = value.firstOrNull()
        val close = if (quote == '"' || quote == '\'') value.indexOf(quote, startIndex = 1) else -1
        return when {
            close > 0 -> value.substring(1, close)
            value.startsWith("#") -> ""
            else -> value.substringBefore(" #").trim()
        }
    }

    private fun unescape(text: String): String =
        text
            .replace("\\n", " ")
            .replace("\\t", " ")
            .replace("\\'", "'")
            .replace("\\\"", "\"")

    /** The text without a byte order mark and with LF line endings. */
    fun normalized(text: String): String = text.removePrefix(BYTE_ORDER_MARK).replace("\r\n", "\n")
}

/** Reading the site folder for [SitePagesRules]. */
object SiteFiles {
    /**
     * Every [SitePagesRules.REQUIRED_FILES] entry by name (null when it is missing) and every other `*.md` file under [dir]
     * by its `/`-separated path relative to [dir], so the copy and TODO rules cover every page Jekyll can publish.
     */
    fun read(dir: File): Map<String, String?> {
        val required =
            SitePagesRules.REQUIRED_FILES.associateWith { name -> dir.resolve(name).takeIf { it.isFile }?.readText(Charsets.UTF_8) }
        val pages =
            dir
                .walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText(Charsets.UTF_8) }
        return required + pages
    }
}

/** Checks the site pages against the rules and the app, and fails with every problem it finds. */
abstract class CheckSitePagesTask : DefaultTask() {
    /** `docs/site/`; may be missing (each page is then reported missing). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val siteDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appLinks: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appStrings: RegularFileProperty

    @get:Input
    abstract val mirroredKeys: ListProperty<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val dir = siteDir.get().asFile
        val files = SiteFiles.read(dir)
        val links =
            Properties()
                .apply {
                    appLinks
                        .get()
                        .asFile
                        .reader(Charsets.UTF_8)
                        .use { load(it) }
                }.let { properties ->
                    properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
                }
        val input =
            SiteInput(
                files = files,
                appLinks = links,
                appStrings = SiteText.strings(appStrings.get().asFile.readText(Charsets.UTF_8)),
                mirroredKeys = mirroredKeys.get(),
            )
        val problems = SitePagesRules.violations(input)
        if (problems.isNotEmpty()) {
            throw GradleException(
                "$CHECK_SITE_PAGES found ${problems.size} problem(s) in ${dir.name}/ (Story 5.7):\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        marker.get().asFile.writeText(
            "Pages OK: ${SitePagesRules.PAGES.joinToString()}; ${mirroredKeys.get().size} mirrored strings\n",
        )
    }
}

/** The `sitePages { }` settings of [SitePagesPlugin]. */
abstract class SitePagesExtension {
    /** The published site, `docs/site/`. */
    abstract val siteDir: DirectoryProperty

    /** `config/app-links.properties`: the page URLs and the support address. */
    abstract val appLinks: RegularFileProperty

    /** The app's Compose `strings.xml`, the source of the text `payments.md` mirrors. */
    abstract val appStrings: RegularFileProperty

    /** The in-app strings `payments.md` must contain word for word. */
    abstract val mirroredKeys: ListProperty<String>
}

/** Registers [CHECK_SITE_PAGES] (a `qualityGate` dependency) on the root project. */
class SitePagesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "Apply yawnandpawn.site-pages to the root project" }
        val extension = target.extensions.create("sitePages", SitePagesExtension::class.java)
        target.tasks.register(CHECK_SITE_PAGES, CheckSitePagesTask::class.java) {
            group = "verification"
            description = "Checks the GitHub Pages site in docs/site: pages, headings, copy rules, links and mirrored payments text."
            siteDir.set(extension.siteDir)
            appLinks.set(extension.appLinks)
            appStrings.set(extension.appStrings)
            mirroredKeys.set(extension.mirroredKeys)
            marker.set(target.layout.buildDirectory.file("checkSitePages/ok.txt"))
        }
    }
}
