package com.carlink.car

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * What Settings › Vehicle shares with the phone, in Apple's terms: the names the adapter declares (`vehicleStatus:`
 * and `iapConfig.vehicleInfo.engineTypes`, OCBM `iap2_core::vehicle`) and the values it sends as VehicleStatusUpdate
 * (0xA101), `[id][value]…` with Apple's parameter ids (the Mac's `i2mspec_dump.py` of 10 Nov). Plain Kotlin, so it is
 * tested without a car.
 *
 * A one-fuel car's range and warning are Apple's unified Range and RangeWarning; the per-fuel ones are for cars with
 * two engines and aren't sent.
 */
object VehicleStatusValues {
    /** 0xA101's Range: uint16, km. */
    const val RANGE = 3

    /** 0xA101's OutsideTemperature: int16, °C. */
    const val OUTSIDE_TEMPERATURE = 4

    /** 0xA101's RangeWarning: bool. */
    const val RANGE_WARNING = 6

    private const val METRES_PER_KM = 1000f
    private const val U16_MAX = 0xFFFF
    private const val BYTE = 0xFF
    private const val BITS = 8

    /** Which of the car's data points the driver shares with the phone. */
    data class Shares(
        val fuelType: Boolean = false,
        val range: Boolean = false,
        val lowFuel: Boolean = false,
        val outsideTemperature: Boolean = false,
    ) {
        /** The 0xA101 ids these shares send. */
        val ids: Set<Int>
            get() =
                buildSet {
                    if (range) add(RANGE)
                    if (outsideTemperature) add(OUTSIDE_TEMPERATURE)
                    if (lowFuel) add(RANGE_WARNING)
                }
    }

    /** The status names the adapter declares for [shares] (`vehicleStatus:`), in Apple's spelling. */
    fun declared(shares: Shares): List<String> =
        buildList {
            if (shares.range) add("range")
            if (shares.outsideTemperature) add("outsideTemperature")
            if (shares.lowFuel) add("rangeWarning")
        }

    /**
     * Apple's engine types (iAP2 EngineType: gasoline, diesel, electric, cng) for Android's FuelType values, in order and
     * without repeats; one Apple has no name for (LPG, hydrogen) is left out.
     */
    fun engines(fuelTypes: List<Int>?): List<String> = fuelTypes.orEmpty().mapNotNull(::engineOf).distinct()

    /** FuelType (android.car.FuelType) -> Apple's engine name, or null. */
    fun engineOf(fuelType: Int): String? =
        when (fuelType) {
            FUEL_UNLEADED, FUEL_LEADED, FUEL_E85 -> "gasoline"
            FUEL_DIESEL_1, FUEL_DIESEL_2, FUEL_BIODIESEL -> "diesel"
            FUEL_ELECTRIC -> "electric"
            FUEL_CNG -> "cng"
            else -> null
        }

    /** [state]'s values for [ids], as `[id][value]…`; empty when none of them has a reading. */
    fun encode(
        state: VehicleState,
        ids: Set<Int>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (RANGE in ids) state.rangeM?.let { out.u16(RANGE, (it / METRES_PER_KM).roundToInt().coerceIn(0, U16_MAX)) }
        if (OUTSIDE_TEMPERATURE in ids) state.outsideC?.let { out.u16(OUTSIDE_TEMPERATURE, it.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())) }
        if (RANGE_WARNING in ids) {
            state.fuelLow?.let {
                out.write(RANGE_WARNING)
                out.write(if (it) 1 else 0)
            }
        }
        return out.toByteArray()
    }

    /** One id with a 16-bit value, big-endian (an int16 as its two's complement). */
    private fun ByteArrayOutputStream.u16(
        id: Int,
        v: Int,
    ) {
        write(id)
        write((v shr BITS) and BYTE)
        write(v and BYTE)
    }

    // android.car.FuelType: the values INFO_FUEL_TYPE reports.
    private const val FUEL_UNLEADED = 1
    private const val FUEL_LEADED = 2
    private const val FUEL_DIESEL_1 = 3
    private const val FUEL_DIESEL_2 = 4
    private const val FUEL_BIODIESEL = 5
    private const val FUEL_E85 = 6
    private const val FUEL_CNG = 8
    private const val FUEL_ELECTRIC = 10
}
