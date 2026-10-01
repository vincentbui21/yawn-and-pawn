package com.yawnandpawn.app.core.session

import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one JSON format of a stored [SessionState] (AD-2). Only this is used to store the session, so the stored format
 * changes only here.
 * - Unknown keys are ignored, so a row written by a newer version still decodes after a downgrade.
 * - The variant name sits in `type` (the `@SerialName` of each state), named explicitly so a library default change
 *   cannot break stored rows.
 * - Defaults are written out, so a later change of a default never changes what an old row means.
 *
 * `SessionJsonTest` decodes a committed version 1 fixture of every active state; a change that breaks it needs a
 * migration of stored rows.
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

    /**
     * The state stored as [text], or [StoredSession.Unreadable] for any failure to decode it, naming only the error
     * type (never the text, which holds the alarm label). Whatever the decoder throws means the row cannot be used;
     * only cancellation propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    fun decode(text: String): StoredSession =
        try {
            StoredSession.Found(json.decodeFromString(SessionState.serializer(), text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StoredSession.Unreadable(e::class.simpleName ?: "Exception")
        }
}
