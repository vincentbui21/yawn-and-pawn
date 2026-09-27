package com.yawnandpawn.app.core.id

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Port for new entity ids (UUID v4 strings), so tests get predictable ids. */
fun interface IdGenerator {
    fun newId(): String
}

/** Random UUID v4 from `kotlin.uuid`, in the standard lowercase 8-4-4-4-12 form. */
class UuidV4IdGenerator : IdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    override fun newId(): String = Uuid.random().toString()
}
