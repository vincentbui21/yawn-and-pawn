package com.yawnandpawn.app.core.reliability

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/**
 * The phone makers whose own battery tools stop background apps, each with its own guidance (Story 5.5,
 * `docs/oem-guidance.md`). [Other] has none: its Manufacturer row is Hidden.
 */
enum class DeviceMaker {
    /** Xiaomi, Redmi and POCO (MIUI, HyperOS). */
    Xiaomi,
    Samsung,

    /** Huawei and Honor (EMUI, Magic OS). */
    Huawei,

    /** Oppo and Realme (ColorOS, Realme UI). */
    OppoRealme,

    /** Vivo and iQOO (Funtouch OS, OriginOS). */
    Vivo,
    OnePlus,

    /** Any other maker: no guidance, the Manufacturer row is Hidden. */
    Other,
    ;

    /** The guidance covers this maker: its Manufacturer row shows. */
    val covered: Boolean get() = this != Other

    companion object {
        private val ALIASES: Map<String, DeviceMaker> =
            mapOf(
                "xiaomi" to Xiaomi,
                "redmi" to Xiaomi,
                "poco" to Xiaomi,
                "samsung" to Samsung,
                "huawei" to Huawei,
                "honor" to Huawei,
                "oppo" to OppoRealme,
                "realme" to OppoRealme,
                "vivo" to Vivo,
                "iqoo" to Vivo,
                "oneplus" to OnePlus,
            )

        /**
         * The maker of a phone that reports [manufacturer] (`Build.MANUFACTURER`) and [brand] (`Build.BRAND`): an exact
         * alias in any case, surrounding spaces ignored, the manufacturer first, then the brand; anything else is [Other].
         */
        fun of(
            manufacturer: String,
            brand: String = "",
        ): DeviceMaker = alias(manufacturer) ?: alias(brand) ?: Other

        private fun alias(name: String): DeviceMaker? = ALIASES[name.trim().lowercase()]
    }
}

/**
 * Port: opens the maker's own settings screen for background apps, only ever from a user tap ("Open settings"). A
 * screen this phone does not have falls back to the app's details page, then to the system settings; never throws.
 */
fun interface ManufacturerSettings {
    fun open(maker: DeviceMaker)
}

/**
 * "I've done this" on the maker guidance (Story 5.5): stores a [MakerConfirmation] of this phone's maker and build
 * fingerprint, so a system update (a new fingerprint) turns the Manufacturer row Revoked. It also adds the row to the
 * rows seen OK, so that update reads Revoked even when the checklist was not opened in between.
 *
 * The confirmation write decides the result: its failure is logged and returned (the user taps again). A failure to
 * read or write the rows seen OK afterwards is logged only, and the confirmation still counts.
 */
class ConfirmMakerGuidance(
    private val device: DeviceInfoSource,
    private val records: ReliabilityRecords,
    private val logger: Logger,
) {
    suspend operator fun invoke(): Outcome<Unit, DomainError> {
        val phone = device.current()
        val confirmed = records.confirmMaker(MakerConfirmation(phone.maker, phone.fingerprint))
        if (confirmed is Outcome.Failure) {
            logger.log(LogEvent.OperationFailed.of(CONFIRM, confirmed.error))
            return confirmed
        }
        when (val stored = records.read()) {
            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(SEEN_OK, stored.error))
            }

            is Outcome.Success -> {
                val lastOk = stored.value.lastOk
                if (ChecklistRow.Manufacturer !in lastOk) {
                    val saved = records.saveLastOk(lastOk + ChecklistRow.Manufacturer)
                    if (saved is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(SEEN_OK, saved.error))
                }
            }
        }
        return Outcome.Success(Unit)
    }

    companion object {
        /** The log operation of a failed confirmation. */
        const val CONFIRM = "confirm maker guidance"

        /** The log operation of a failed update of the rows seen OK after a confirmation. */
        const val SEEN_OK = "mark maker guidance seen OK"
    }
}
