/*
 *  Copyright (C) 2025  olie.xdev <olie.xdev@googlemail.com>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package com.health.openscale.sync.core.sync.garmin

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal FIT-protocol weight-scale file encoder — a Kotlin port of the FitEncoderWeight
 * from the python-garminconnect project. Produces exactly the file a real scale (e.g. a
 * Garmin Index) uploads: file_id + file_creator + device_info + weight_scale messages.
 *
 * Usage:
 *   val fit = FitWeightEncoder()
 *   fit.writeFileInfo(); fit.writeFileCreator(); fit.writeDeviceInfo(epochSeconds)
 *   fit.writeWeightScale(epochSeconds, weight = 72.5, percentFat = 18.2, ...)
 *   val bytes = fit.finish()
 */
class FitWeightEncoder {

    // FIT base types (field byte includes the endian bit 0x80 for multi-byte LE types)
    private enum class BaseType(val size: Int, val field: Int, val invalid: Long) {
        ENUM(1, 0x00, 0xFF),
        UINT8(1, 0x02, 0xFF),
        UINT16(2, 0x84, 0xFFFF),
        UINT32(4, 0x86, 0xFFFFFFFFL),
        UINT32Z(4, 0x8C, 0x00000000L)
    }

    private class Field(val num: Int, val type: BaseType, val value: Double?, val scale: Int?)

    private val buf = ByteArrayOutputStream()
    private var deviceInfoDefined = false
    private var weightScaleDefined = false

    init {
        writeHeader(dataSize = 0)
    }

    // --- public message writers --------------------------------------------------

    fun writeFileInfo(timeCreatedSec: Long = System.currentTimeMillis() / 1000) {
        val content = listOf(
            Field(3, BaseType.UINT32Z, null, null),                       // serial number
            Field(4, BaseType.UINT32, fitTimestamp(timeCreatedSec), null),// time created
            Field(1, BaseType.UINT16, null, null),                        // manufacturer
            Field(2, BaseType.UINT16, null, null),                        // product
            Field(5, BaseType.UINT16, null, null),                        // number
            Field(0, BaseType.ENUM, FILE_TYPE_WEIGHT_SCALE.toDouble(), null)
        )
        writeDefinitionAndRecord(GMSG_FILE_ID, LMSG_FILE_INFO, content)
    }

    fun writeFileCreator() {
        val content = listOf(
            Field(0, BaseType.UINT16, null, null),   // software version
            Field(1, BaseType.UINT8, null, null)     // hardware version
        )
        writeDefinitionAndRecord(GMSG_FILE_CREATOR, LMSG_FILE_CREATOR, content)
    }

    fun writeDeviceInfo(timestampSec: Long) {
        val content = listOf(
            Field(253, BaseType.UINT32, fitTimestamp(timestampSec), 1),
            Field(3, BaseType.UINT32Z, null, 1),
            Field(7, BaseType.UINT32, null, 1),
            Field(8, BaseType.UINT32, null, null),   // undocumented
            Field(2, BaseType.UINT16, null, 1),
            Field(4, BaseType.UINT16, null, 1),
            Field(5, BaseType.UINT16, null, 100),
            Field(10, BaseType.UINT16, null, 256),
            Field(0, BaseType.UINT8, null, 1),
            Field(1, BaseType.UINT8, null, 1),
            Field(6, BaseType.UINT8, null, 1),
            Field(11, BaseType.UINT8, null, null)
        )
        if (!deviceInfoDefined) {
            writeDefinition(GMSG_DEVICE_INFO, LMSG_DEVICE_INFO, content)
            deviceInfoDefined = true
        }
        writeRecord(LMSG_DEVICE_INFO, content)
    }

    fun writeWeightScale(
        timestampSec: Long,
        weight: Double,                       // kg
        percentFat: Double? = null,           // %
        percentHydration: Double? = null,     // %
        visceralFatMass: Double? = null,      // kg
        boneMass: Double? = null,             // kg
        muscleMass: Double? = null,           // kg
        basalMet: Double? = null,             // kcal/day
        activeMet: Double? = null,            // kcal/day
        physiqueRating: Double? = null,
        metabolicAge: Double? = null,         // years
        visceralFatRating: Double? = null,    // level
        bmi: Double? = null
    ) {
        val content = listOf(
            Field(253, BaseType.UINT32, fitTimestamp(timestampSec), 1),
            Field(0, BaseType.UINT16, weight, 100),
            Field(1, BaseType.UINT16, percentFat, 100),
            Field(2, BaseType.UINT16, percentHydration, 100),
            Field(3, BaseType.UINT16, visceralFatMass, 100),
            Field(4, BaseType.UINT16, boneMass, 100),
            Field(5, BaseType.UINT16, muscleMass, 100),
            Field(7, BaseType.UINT16, basalMet, 4),
            Field(9, BaseType.UINT16, activeMet, 4),
            Field(8, BaseType.UINT8, physiqueRating, 1),
            Field(10, BaseType.UINT8, metabolicAge, 1),
            Field(11, BaseType.UINT8, visceralFatRating, 1),
            Field(13, BaseType.UINT16, bmi, 10)
        )
        if (!weightScaleDefined) {
            writeDefinition(GMSG_WEIGHT_SCALE, LMSG_WEIGHT_SCALE, content)
            weightScaleDefined = true
        }
        writeRecord(LMSG_WEIGHT_SCALE, content)
    }

    /** Re-write the header with the final data size and append the CRC. */
    fun finish(): ByteArray {
        val dataSize = buf.size() - HEADER_SIZE
        val all = buf.toByteArray()
        buf.reset()
        writeHeader(dataSize)
        buf.write(all, HEADER_SIZE, all.size - HEADER_SIZE)
        val bytes = buf.toByteArray()
        val crc = crc16(bytes)
        buf.write(crc and 0xFF)
        buf.write((crc shr 8) and 0xFF)
        return buf.toByteArray()
    }

    // --- internals -----------------------------------------------------------------

    private fun writeHeader(dataSize: Int) {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(HEADER_SIZE.toByte())
        b.put(16)                       // protocol version
        b.putShort(108)                 // profile version
        b.putInt(dataSize)
        b.put(".FIT".toByteArray(Charsets.US_ASCII))
        buf.write(b.array())
    }

    private fun recordHeader(definition: Boolean, lmsgType: Int): Int =
        (if (definition) 0x40 else 0x00) or lmsgType

    private fun writeDefinition(gmsgNum: Int, lmsgType: Int, content: List<Field>) {
        buf.write(recordHeader(true, lmsgType))
        buf.write(0)                    // reserved
        buf.write(0)                    // architecture: little endian
        val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(gmsgNum.toShort())
        buf.write(b.array())
        buf.write(content.size)
        content.forEach { f ->
            buf.write(f.num)
            buf.write(f.type.size)
            buf.write(f.type.field)
        }
    }

    private fun writeRecord(lmsgType: Int, content: List<Field>) {
        buf.write(recordHeader(false, lmsgType))
        content.forEach { f -> writeValue(f) }
    }

    private fun writeDefinitionAndRecord(gmsgNum: Int, lmsgType: Int, content: List<Field>) {
        writeDefinition(gmsgNum, lmsgType, content)
        writeRecord(lmsgType, content)
    }

    private fun writeValue(f: Field) {
        val scaled: Long = if (f.value == null) {
            f.type.invalid
        } else {
            (f.value * (f.scale ?: 1)).toLong() and f.type.invalid
        }
        val b = ByteBuffer.allocate(f.type.size).order(ByteOrder.LITTLE_ENDIAN)
        when (f.type.size) {
            1 -> b.put(scaled.toByte())
            2 -> b.putShort(scaled.toShort())
            else -> b.putInt(scaled.toInt())
        }
        buf.write(b.array())
    }

    /** FIT timestamps are seconds since 1989-12-31 00:00 UTC. */
    private fun fitTimestamp(epochSec: Long): Double = (epochSec - FIT_EPOCH_OFFSET).toDouble()

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            val b = byte.toInt()
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }

    companion object {
        private const val HEADER_SIZE = 12
        private const val FILE_TYPE_WEIGHT_SCALE = 9
        private const val FIT_EPOCH_OFFSET = 631065600L

        private const val GMSG_FILE_ID = 0
        private const val GMSG_DEVICE_INFO = 23
        private const val GMSG_WEIGHT_SCALE = 30
        private const val GMSG_FILE_CREATOR = 49

        private const val LMSG_FILE_INFO = 0
        private const val LMSG_FILE_CREATOR = 1
        private const val LMSG_DEVICE_INFO = 2
        private const val LMSG_WEIGHT_SCALE = 3

        private val CRC_TABLE = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
        )
    }
}
