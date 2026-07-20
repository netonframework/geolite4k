package com.netonframework.geolite4k

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

@OptIn(ExperimentalForeignApi::class)
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
internal actual fun readFileBytes(path: String): ByteArray? = memScoped {
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val length = ftell(file).toLong()
        if (length < 0 || length > Int.MAX_VALUE) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        if (length == 0L) return ByteArray(0)
        val buffer = allocArray<ByteVar>(length.toInt())
        val count = fread(buffer, 1u, length.toULong(), file).toLong()
        if (count != length) return null
        buffer.readBytes(length.toInt())
    } finally {
        fclose(file)
    }
}
