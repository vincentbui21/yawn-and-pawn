package com.yawnandpawn.app.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one JSON format of a stored [SessionState] (AD-2). Only this is used to store the session, so the stored format
 * changes only here.
 * - Unknown keys are ignored, so a row written by a newer version still decodes after a downgrade.
 * - The variant name sits in `type` (the `@SerialName` of each state), named explicitly so a library default change
 *   cannot break stored rows.
 * - Defaults are written out, so a later change of a default never changes what an old row means.
 * - Rows written before Story 3.1 are migrated before decoding (see [LegacyCheckFormat]).
 *
 * `SessionJsonTest` decodes a committed version 1 and version 2 fixture of every active state; a change that breaks
 * them needs a migration of stored rows.
 */
object SessionJson {
    /** The variant field of every encoded state. */
    const val CLASS_DISCRIMINATOR = "type"

    val json: Json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = CLASS_DISCRIMINATOR
            encodeDefaults = true
        }

    /** [state] as stored JSON. */
    fun encode(state: SessionState): String = json.encodeToString(SessionState.serializer(), state)

    /** [config] as stored JSON (the pending test ring, Story 1.18). */
    fun encodeConfig(config: SessionConfig): String = json.encodeToString(SessionConfig.serializer(), config)

    /** The config stored as [text], or null when it cannot be decoded (decoding never suspends). */
    fun decodeConfig(text: String): SessionConfig? =
        runCatching {
            json.decodeFromJsonElement(SessionConfig.serializer(), LegacyCheckFormat.config(json.parseToJsonElement(text)))
        }.getOrNull()

    /**
     * The state stored as [text], or [StoredSession.Unreadable] for any failure to decode it, naming only the error
     * type (never the text, which holds the alarm label). Whatever the decoder throws means the row cannot be used;
     * only cancellation propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    fun decode(text: String): StoredSession =
        try {
            StoredSession.Found(
                json.decodeFromJsonElement(SessionState.serializer(), LegacyCheckFormat.state(json.parseToJsonElement(text))),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StoredSession.Unreadable(e::class.simpleName ?: "Exception")
        }
}

/**
 * Version 1 (Epics 1–2) stored the check as a plan of steps and an integer step: `"checkPlan":{"steps":[{"type":
 * "Placeholder"}]}` and `"step":1`. Story 3.1 stores `CheckPlan(mode, entries)` and a `StepPointer`. A version 1 plan
 * becomes an All plan with one entry per step (count 1, Medium), and step `n` becomes entry `n`, item 0. Anything else
 * passes through unchanged, so a damaged row still fails to decode.
 */
internal object LegacyCheckFormat {
    fun state(element: JsonElement): JsonElement = element.edit("session", ::session)

    fun config(element: JsonElement): JsonElement = element.edit("checkPlan", ::plan)

    private fun session(element: JsonElement): JsonElement = element.edit("config", ::config).edit("checkRun", ::run)

    private fun run(element: JsonElement): JsonElement =
        element.edit("plan", ::plan).edit("step") { step ->
            val index = (step as? JsonPrimitive)?.intOrNull
            if (index == null) {
                step
            } else {
                buildJsonObject {
                    put("entry", index)
                    put("item", 0)
                }
            }
        }

    private fun plan(element: JsonElement): JsonElement {
        val plan = element as? JsonObject
        val steps = plan?.get("steps") as? JsonArray
        if (steps == null || plan.containsKey("entries")) return element
        return buildJsonObject {
            put("mode", "All")
            put(
                "entries",
                JsonArray(
                    steps.map { step ->
                        buildJsonObject {
                            put("type", step)
                            put("difficulty", "Medium")
                            put("count", 1)
                        }
                    },
                ),
            )
        }
    }

    /** This object with [key] replaced by [transform] of its value; anything else unchanged. */
    private fun JsonElement.edit(
        key: String,
        transform: (JsonElement) -> JsonElement,
    ): JsonElement {
        val value = (this as? JsonObject)?.get(key) ?: return this
        return JsonObject(this + (key to transform(value)))
    }
}
