package com.netonframework.geolite4k

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fread

private const val CHUNK = 64 * 1024

/**
 * 按块读到 EOF，不用 fseek/ftell 先取长度。
 *
 * 那两个函数的 offset 与返回值是 C 的 long——Windows 上 32 位，Linux/macOS 上 64 位——
 * 在共享的 nativeMain 里引用它们，元数据编译会直接报「不同平台位宽不一致」，
 * 且 convert() 救不了：这条诊断针对的是引用本身，不是类型转换。
 * fread 收发的是 size_t，五个目标上都是 64 位，所以只用它就没有这个问题。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun readFileBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        val chunks = ArrayList<ByteArray>()
        var total = 0L
        memScoped {
            val buffer = allocArray<ByteVar>(CHUNK)
            while (true) {
                val read: Long = fread(buffer, 1.convert(), CHUNK.convert(), file).convert()
                if (read <= 0L) break
                total += read
                if (total > Int.MAX_VALUE) return null
                chunks.add(buffer.readBytes(read.toInt()))
            }
        }
        if (ferror(file) != 0) return null

        val out = ByteArray(total.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    } finally {
        fclose(file)
    }
}
