package com.yawnandpawn.app.ui.alarms

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no alarms is the empty state`() =
        runTest(dispatcher) {
            val viewModel = AlarmsViewModel(FakeAlarmRepository())
            backgroundScope.launch { viewModel.state.collect { } }

            assertEquals(AlarmsUiState(isLoading = false, alarms = emptyList()), viewModel.state.value)
        }

    @Test
    fun `rows follow the repository in list order and update after a save`() =
        runTest(dispatcher) {
            val repository =
                FakeAlarmRepository(listOf(anAlarm(id = FakeIdGenerator.fakeUuid(1), time = LocalTime(22, 0), label = "Late")))
            val viewModel = AlarmsViewModel(repository)
            backgroundScope.launch { viewModel.state.collect { } }

            repository.upsert(anAlarm(id = FakeIdGenerator.fakeUuid(2), time = LocalTime(6, 30), requestCode = 1001))

            assertEquals(
                listOf(
                    AlarmRow(FakeIdGenerator.fakeUuid(2), LocalTime(6, 30), emptySet(), null),
                    AlarmRow(FakeIdGenerator.fakeUuid(1), LocalTime(22, 0), emptySet(), "Late"),
                ),
                viewModel.state.value.alarms,
            )
        }

    @Test
    fun `a storage failure is a failed state, not the empty state`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository().apply { failure = DomainError.StorageFailure("closed") }
            val viewModel = AlarmsViewModel(repository)
            backgroundScope.launch { viewModel.state.collect { } }

            assertEquals(AlarmsUiState(isLoading = false, loadFailed = true), viewModel.state.value)
        }
}
