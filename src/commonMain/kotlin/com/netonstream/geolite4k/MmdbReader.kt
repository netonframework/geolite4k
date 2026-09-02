package com.netonstream.geolite4k

internal data class MmdbMetadata(
    val nodeCount: Long,
    val recordSize: Int,
    val ipVersion: Int,
    val databaseType: String,
    val buildEpoch: Long,
)

internal sealed class MmdbLookupResult {
    data class Found(val value: MmdbValue) : MmdbLookupResult()
    data object NotFound : MmdbLookupResult()
    data object InvalidIp : MmdbLookupResult()
}

internal sealed class MmdbValue {
    data class MapValue(val value: Map<String, MmdbValue>) : MmdbValue()
    data class ArrayValue(val value: List<MmdbValue>) : MmdbValue()
    data class StringValue(val value: String) : MmdbValue()
    data class BytesValue(val value: ByteArray) : MmdbValue()
    data class IntegerValue(val value: Long) : MmdbValue()
    data class DoubleValue(val value: Double) : MmdbValue()
    data class BooleanValue(val value: Boolean) : MmdbValue()
    data object NullValue : MmdbValue()

    fun asMap(): Map<String, MmdbValue>? = (this as? MapValue)?.value
    fun asArray(): List<MmdbValue>? = (this as? ArrayValue)?.value
    fun asString(): String? = (this as? StringValue)?.value
    fun asNumber(): Double? = when (this) {
        is DoubleValue -> value
        is IntegerValue -> value.toDouble()
        else -> null
    }
}

internal class MmdbReader(private val bytes: ByteArray) {
    companion object {
        private val METADATA_MARKER = byteArrayOf(
            0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
            'M'.code.toByte(), 'a'.code.toByte(), 'x'.code.toByte(),
            'M'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(),
            'd'.code.toByte(), '.'.code.toByte(), 'c'.code.toByte(),
            'o'.code.toByte(), 'm'.code.toByte(),
        )
    }

    private val metadataStart = bytes.lastIndexOf(METADATA_MARKER).let { marker ->
        require(marker >= 0) { "MaxMind metadata marker not found" }
        marker + METADATA_MARKER.size
    }
    private val metadataMap = Decoder(bytes, metadataStart).decode(metadataStart).value.asMap()
        ?: error("MaxMind metadata is not a map")

    val metadata = MmdbMetadata(
        nodeCount = metadataMap.requiredLong("node_count"),
        recordSize = metadataMap.requiredLong("record_size").toInt(),
        ipVersion = metadataMap.requiredLong("ip_version").toInt(),
        databaseType = metadataMap["database_type"]?.asString() ?: "unknown",
        buildEpoch = metadataMap.requiredLong("build_epoch"),
    )

    private val nodeByteSize = metadata.recordSize * 2 / 8
    private val searchTreeSize = metadata.nodeCount * nodeByteSize
    private val dataSectionStart = searchTreeSize + 16
    private val dataDecoder = Decoder(bytes, dataSectionStart.toInt())

    init {
        require(metadata.recordSize in setOf(24, 28, 32)) {
            "unsupported MaxMind record size: ${metadata.recordSize}"
        }
        require(metadata.ipVersion == 4 || metadata.ipVersion == 6) {
            "invalid MaxMind IP version: ${metadata.ipVersion}"
        }
        require(dataSectionStart < metadataStart) { "invalid MaxMind section offsets" }
    }

    fun lookup(ip: String): MmdbLookupResult {
        val parsed = parseIp(ip) ?: return MmdbLookupResult.InvalidIp
        if (metadata.ipVersion == 4 && parsed.size == 16) return MmdbLookupResult.NotFound
        val address = when {
            metadata.ipVersion == 6 && parsed.size == 4 -> ByteArray(12) + parsed
            else -> parsed
        }

        var node = 0L
        for (byte in address) {
            for (bitIndex in 7 downTo 0) {
                val bit = (byte.toInt() ushr bitIndex) and 1
                node = readNode(node, bit)
                when {
                    node == metadata.nodeCount -> return MmdbLookupResult.NotFound
                    node > metadata.nodeCount -> {
                        val offset = (node - metadata.nodeCount + searchTreeSize).toInt()
                        return MmdbLookupResult.Found(dataDecoder.decode(offset).value)
                    }
                }
            }
        }
        return MmdbLookupResult.NotFound
    }

    private fun readNode(node: Long, bit: Int): Long {
        require(node in 0 until metadata.nodeCount) { "invalid MaxMind node: $node" }
        val offset = (node * nodeByteSize).toInt()
        return when (metadata.recordSize) {
            24 -> readUnsigned(offset + bit * 3, 3)
            28 -> if (bit == 0) {
                ((bytes.u(offset + 3) ushr 4).toLong() shl 24) or readUnsigned(offset, 3)
            } else {
                ((bytes.u(offset + 3) and 0x0F).toLong() shl 24) or readUnsigned(offset + 4, 3)
            }
            32 -> readUnsigned(offset + bit * 4, 4)
            else -> error("unsupported record size")
        }
    }

    private fun readUnsigned(offset: Int, size: Int): Long {
        require(offset >= 0 && offset + size <= bytes.size) { "MaxMind read outside database" }
        var value = 0L
        repeat(size) { index -> value = (value shl 8) or bytes.u(offset + index).toLong() }
        return value
    }
}

private data class DecodeResult(val value: MmdbValue, val nextOffset: Int)

private class Decoder(
    private val bytes: ByteArray,
    private val pointerBase: Int,
) {
    fun decode(offset: Int, depth: Int = 0): DecodeResult {
        require(depth < 128) { "MaxMind value nesting is too deep" }
        require(offset in bytes.indices) { "MaxMind value offset outside database: $offset" }
        val control = bytes.u(offset)
        var cursor = offset + 1
        var type = control ushr 5
        if (type == 0) {
            require(cursor < bytes.size) { "truncated MaxMind extended type" }
            type = bytes.u(cursor) + 7
            cursor++
        }

        if (type == 1) return decodePointer(control, cursor, depth)
        val sizeResult = decodeSize(control and 0x1F, cursor)
        val size = sizeResult.first
        cursor = sizeResult.second
        require(size >= 0) { "negative MaxMind value size" }

        return when (type) {
            2 -> DecodeResult(MmdbValue.StringValue(readBytes(cursor, size).decodeToString()), cursor + size)
            3 -> DecodeResult(MmdbValue.DoubleValue(Double.fromBits(readUnsigned(cursor, size))), cursor + size)
            4 -> DecodeResult(MmdbValue.BytesValue(readBytes(cursor, size)), cursor + size)
            5, 6, 9, 10 -> DecodeResult(MmdbValue.IntegerValue(readUnsigned(cursor, size)), cursor + size)
            7 -> decodeMap(size, cursor, depth)
            8 -> DecodeResult(MmdbValue.IntegerValue(readSigned(cursor, size)), cursor + size)
            11 -> decodeArray(size, cursor, depth)
            13 -> DecodeResult(MmdbValue.NullValue, cursor)
            14 -> DecodeResult(MmdbValue.BooleanValue(size != 0), cursor)
            15 -> DecodeResult(MmdbValue.DoubleValue(Float.fromBits(readUnsigned(cursor, size).toInt()).toDouble()), cursor + size)
            else -> error("unsupported MaxMind value type: $type")
        }
    }

    private fun decodePointer(control: Int, cursor: Int, depth: Int): DecodeResult {
        val pointerSizeCode = (control ushr 3) and 0x03
        val pointerBytes = pointerSizeCode + 1
        require(cursor + pointerBytes <= bytes.size) { "truncated MaxMind pointer" }
        var pointer = if (pointerSizeCode == 3) 0L else (control and 0x07).toLong()
        repeat(pointerBytes) { index -> pointer = (pointer shl 8) or bytes.u(cursor + index).toLong() }
        pointer += when (pointerSizeCode) {
            1 -> 2_048L
            2 -> 526_336L
            else -> 0L
        }
        val target = pointerBase + pointer.toInt()
        return DecodeResult(decode(target, depth + 1).value, cursor + pointerBytes)
    }

    private fun decodeMap(size: Int, start: Int, depth: Int): DecodeResult {
        var cursor = start
        val values = LinkedHashMap<String, MmdbValue>(size)
        repeat(size) {
            val key = decode(cursor, depth + 1)
            cursor = key.nextOffset
            val keyString = key.value.asString() ?: error("MaxMind map key is not a string")
            val value = decode(cursor, depth + 1)
            cursor = value.nextOffset
            values[keyString] = value.value
        }
        return DecodeResult(MmdbValue.MapValue(values), cursor)
    }

    private fun decodeArray(size: Int, start: Int, depth: Int): DecodeResult {
        var cursor = start
        val values = ArrayList<MmdbValue>(size)
        repeat(size) {
            val value = decode(cursor, depth + 1)
            cursor = value.nextOffset
            values += value.value
        }
        return DecodeResult(MmdbValue.ArrayValue(values), cursor)
    }

    private fun decodeSize(encoded: Int, cursor: Int): Pair<Int, Int> = when (encoded) {
        in 0..28 -> encoded to cursor
        29 -> (29 + bytes.u(cursor)) to (cursor + 1)
        30 -> (285 + readUnsigned(cursor, 2).toInt()) to (cursor + 2)
        else -> (65_821 + readUnsigned(cursor, 3).toInt()) to (cursor + 3)
    }

    private fun readBytes(offset: Int, size: Int): ByteArray {
        require(offset >= 0 && offset + size <= bytes.size) { "truncated MaxMind value" }
        return bytes.copyOfRange(offset, offset + size)
    }

    private fun readUnsigned(offset: Int, size: Int): Long {
        require(size in 0..8) { "unsupported MaxMind integer size: $size" }
        require(offset >= 0 && offset + size <= bytes.size) { "truncated MaxMind integer" }
        var value = 0L
        repeat(size) { index -> value = (value shl 8) or bytes.u(offset + index).toLong() }
        return value
    }

    private fun readSigned(offset: Int, size: Int): Long {
        val value = readUnsigned(offset, size)
        if (size != 4 || value and 0x8000_0000L == 0L) return value
        return value - 0x1_0000_0000L
    }
}

private fun Map<String, MmdbValue>.requiredLong(key: String): Long =
    (get(key) as? MmdbValue.IntegerValue)?.value ?: error("missing MaxMind metadata: $key")

private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.lastIndexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    for (start in size - needle.size downTo 0) {
        var matches = true
        for (index in needle.indices) {
            if (this[start + index] != needle[index]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}

private fun parseIp(input: String): ByteArray? {
    val value = input.substringBefore('%').trim()
    if (':' !in value) return parseIpv4(value)
    var normalized = value
    if ('.' in normalized) {
        val lastColon = normalized.lastIndexOf(':')
        if (lastColon < 0) return null
        val ipv4 = parseIpv4(normalized.substring(lastColon + 1)) ?: return null
        val first = ((ipv4[0].toInt() and 0xFF) shl 8) or (ipv4[1].toInt() and 0xFF)
        val second = ((ipv4[2].toInt() and 0xFF) shl 8) or (ipv4[3].toInt() and 0xFF)
        normalized = normalized.substring(0, lastColon + 1) + first.toString(16) + ":" + second.toString(16)
    }

    if (normalized.countSubstring("::") > 1) return null
    val compressed = "::" in normalized
    val sides = normalized.split("::", limit = 2)
    val left = sides[0].split(':').filter(String::isNotEmpty)
    val right = if (sides.size == 2) sides[1].split(':').filter(String::isNotEmpty) else emptyList()
    val missing = 8 - left.size - right.size
    if ((!compressed && missing != 0) || (compressed && missing < 1)) return null
    val groups = left + List(missing) { "0" } + right
    if (groups.size != 8) return null

    val result = ByteArray(16)
    groups.forEachIndexed { index, group ->
        if (group.isEmpty() || group.length > 4) return null
        val number = group.toIntOrNull(16) ?: return null
        result[index * 2] = (number ushr 8).toByte()
        result[index * 2 + 1] = number.toByte()
    }
    return result
}

private fun parseIpv4(value: String): ByteArray? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    return ByteArray(4) { index ->
        val part = parts[index]
        if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
        val number = part.toIntOrNull() ?: return null
        if (number !in 0..255) return null
        number.toByte()
    }
}

private fun String.countSubstring(value: String): Int {
    var count = 0
    var start = 0
    while (true) {
        val index = indexOf(value, start)
        if (index < 0) return count
        count++
        start = index + value.length
    }
}
