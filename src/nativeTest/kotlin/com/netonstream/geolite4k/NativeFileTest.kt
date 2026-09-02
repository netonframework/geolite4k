package com.netonstream.geolite4k

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * readFileBytes 按 64 KB 分块读，所以只用 21 KB 的测试库覆盖不到跨块拼接。
 * 这里显式造出跨越多个块、且末块不对齐的输入。
 */
class NativeFileTest {

    @OptIn(ExperimentalForeignApi::class)
    private fun write(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("cannot open $path for writing")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                }
            }
        } finally {
            fclose(file)
        }
    }

    private fun roundTrip(size: Int) {
        val path = "build/native-file-$size.bin"
        val expected = Random(size).nextBytes(size)
        write(path, expected)
        try {
            assertContentEquals(expected, readFileBytes(path), "size=$size")
        } finally {
            remove(path)
        }
    }

    @Test
    fun readsEmptyFile() = roundTrip(0)

    @Test
    fun readsFileSmallerThanOneChunk() = roundTrip(1024)

    @Test
    fun readsFileExactlyOneChunk() = roundTrip(64 * 1024)

    @Test
    fun readsFileSpanningSeveralChunksWithARemainder() = roundTrip(64 * 1024 * 3 + 12345)

    @Test
    fun missingFileIsNull() {
        assertNull(readFileBytes("build/definitely-not-here.bin"))
    }
}
