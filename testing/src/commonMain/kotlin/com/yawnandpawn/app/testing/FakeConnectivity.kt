package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.net.Connectivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [Connectivity] under test control: [online] is the current value, and setting it emits the change to every collector. */
class FakeConnectivity(
    online: Boolean = true,
) : Connectivity {
    private val state = MutableStateFlow(online)

    var online: Boolean
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun observeOnline(): Flow<Boolean> = state
}
