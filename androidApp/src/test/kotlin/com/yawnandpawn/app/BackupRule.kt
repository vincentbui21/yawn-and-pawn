package com.yawnandpawn.app

import android.content.Context
import org.xmlpull.v1.XmlPullParser

/** A backup rule element: the section it sits in (cloud-backup, device-transfer or the root), include/exclude, domain, path. */
data class BackupRule(
    val section: String,
    val kind: String,
    val domain: String,
    val path: String,
)

/** Every include and exclude of the rule file [xmlRes], in document order. */
fun backupRules(
    context: Context,
    xmlRes: Int,
): List<BackupRule> {
    val parser = context.resources.getXml(xmlRes)
    val sections = ArrayDeque<String>()
    val rules = mutableListOf<BackupRule>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "include" || parser.name == "exclude") {
                    rules +=
                        BackupRule(
                            section = sections.last(),
                            kind = parser.name,
                            domain = parser.getAttributeValue(null, "domain"),
                            path = parser.getAttributeValue(null, "path"),
                        )
                } else {
                    sections.addLast(parser.name)
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name != "include" && parser.name != "exclude") sections.removeLast()
            }
        }
    }
    return rules
}
