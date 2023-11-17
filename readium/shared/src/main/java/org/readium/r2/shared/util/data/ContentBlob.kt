/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.data

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.readium.r2.shared.extensions.*
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.MessageError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.toUrl

/**
 * A [Resource] to access content [uri] thanks to a [ContentResolver].
 */
public class ContentBlob(
    private val uri: Uri,
    private val contentResolver: ContentResolver
) : Blob {

    private lateinit var _length: Try<Long, ReadError>

    override val source: AbsoluteUrl? = uri.toUrl() as? AbsoluteUrl

    override suspend fun close() {
    }

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
        if (range == null) {
            return readFully()
        }

        @Suppress("NAME_SHADOWING")
        val range = range
            .coerceFirstNonNegative()
            .requireLengthFitInt()

        if (range.isEmpty()) {
            return Try.success(ByteArray(0))
        }

        return readRange(range)
    }

    private suspend fun readFully(): Try<ByteArray, ReadError> =
        withStream { it.readFully() }

    private suspend fun readRange(range: LongRange): Try<ByteArray, ReadError> =
        withStream {
            withContext(Dispatchers.IO) {
                var skipped: Long = 0

                while (skipped != range.first) {
                    skipped += it.skip(range.first - skipped)
                    if (skipped == 0L) {
                        throw IOException("Could not skip InputStream.")
                    }
                }

                val length = range.last - range.first + 1
                it.read(length)
            }
        }

    override suspend fun length(): Try<Long, ReadError> {
        if (!::_length.isInitialized) {
            _length = Try.catching {
                contentResolver.openFileDescriptor(uri, "r")
                    ?.use { fd -> fd.statSize.takeUnless { it == -1L } }
            }.flatMap {
                when (it) {
                    null -> Try.failure(
                        ReadError.UnsupportedOperation(
                            MessageError("Content provider does not provide length for uri $uri.")
                        )
                    )
                    else -> Try.success(it)
                }
            }
        }

        return _length
    }

    private suspend fun <T> withStream(block: suspend (InputStream) -> T): Try<T, ReadError> {
        return Try.catching {
            val stream = contentResolver.openInputStream(uri)
                ?: return Try.failure(
                    ReadError.Other(
                        Exception("Content provider recently crashed.")
                    )
                )
            val result = block(stream)
            stream.close()
            result
        }
    }

    private inline fun <T> Try.Companion.catching(closure: () -> T): Try<T, ReadError> =
        try {
            success(closure())
        } catch (e: FileNotFoundException) {
            failure(ReadError.Access(ContentProviderError.FileNotFound(e)))
        } catch (e: IOException) {
            failure(ReadError.Access(ContentProviderError.IO(e)))
        } catch (e: OutOfMemoryError) { // We don't want to catch any Error, only OOM.
            failure(ReadError.OutOfMemory(e))
        }

    override fun toString(): String =
        "${javaClass.simpleName}(${runBlocking { length() } } bytes )"
}
