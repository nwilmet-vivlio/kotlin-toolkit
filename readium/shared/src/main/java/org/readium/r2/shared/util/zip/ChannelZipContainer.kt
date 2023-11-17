/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.zip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.extensions.readFully
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.MessageError
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.archive.ArchiveProperties
import org.readium.r2.shared.util.archive.archive
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.ReadException
import org.readium.r2.shared.util.data.unwrapReadException
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.io.CountingInputStream
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.mediatype.MediaTypeHints
import org.readium.r2.shared.util.mediatype.MediaTypeRetriever
import org.readium.r2.shared.util.mediatype.MediaTypeSnifferError
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.ResourceTry
import org.readium.r2.shared.util.tryRecover
import org.readium.r2.shared.util.zip.compress.archivers.zip.ZipArchiveEntry
import org.readium.r2.shared.util.zip.compress.archivers.zip.ZipFile

internal class ChannelZipContainer(
    private val zipFile: ZipFile,
    override val source: AbsoluteUrl?,
    private val mediaTypeRetriever: MediaTypeRetriever
) : Container<Resource> {

    private inner class Entry(
        private val url: Url,
        private val entry: ZipArchiveEntry
    ) : Resource {

        override val source: AbsoluteUrl? get() = null

        override suspend fun properties(): ResourceTry<Resource.Properties> =
            Try.success(
                Resource.Properties {
                    archive = ArchiveProperties(
                        entryLength = compressedLength
                            ?: length().getOrElse { return Try.failure(it) },
                        isEntryCompressed = compressedLength != null
                    )
                }
            )

        override suspend fun mediaType(): ResourceTry<MediaType> =
            mediaTypeRetriever.retrieve(
                hints = MediaTypeHints(fileExtension = url.extension),
                blob = this
            ).tryRecover { error ->
                when (error) {
                    is MediaTypeSnifferError.DataAccess ->
                        Try.failure(error.cause)
                    MediaTypeSnifferError.NotRecognized ->
                        Try.success(MediaType.BINARY)
                }
            }

        override suspend fun length(): ResourceTry<Long> =
            entry.size.takeUnless { it == -1L }
                ?.let { Try.success(it) }
                ?: Try.failure(
                    ReadError.UnsupportedOperation(
                        MessageError("ZIP entry doesn't provide length for entry $url.")
                    )
                )

        private val compressedLength: Long?
            get() =
                if (entry.method == ZipArchiveEntry.STORED || entry.method == -1) {
                    null
                } else {
                    entry.compressedSize.takeUnless { it == -1L }
                }

        override suspend fun read(range: LongRange?): ResourceTry<ByteArray> =
            withContext(Dispatchers.IO) {
                try {
                    val bytes =
                        if (range == null) {
                            readFully()
                        } else {
                            readRange(range)
                        }
                    Try.success(bytes)
                } catch (exception: Exception) {
                    when (val e = exception.unwrapReadException()) {
                        is ReadException ->
                            Try.failure(e.error)
                        else ->
                            Try.failure(ReadError.Decoding(e))
                    }
                }
            }

        private suspend fun readFully(): ByteArray =
            zipFile.getInputStream(entry).use {
                it.readFully()
            }

        private fun readRange(range: LongRange): ByteArray =
            stream(range.first).readRange(range)

        /**
         * Reading an entry in chunks (e.g. from the HTTP server) can be really slow if the entry
         * is deflated in the archive, because we can't jump to an arbitrary offset in a deflated
         * stream. This means that we need to read from the start of the entry for each chunk.
         *
         * To alleviate this issue, we cache a stream which will be reused as long as the chunks are
         * requested in order.
         *
         * See this issue for more info: https://github.com/readium/r2-shared-kotlin/issues/129
         *
         * In case of a stored entry, we create a new stream starting at the desired index in order
         * to prevent downloading of data until [fromIndex].
         *
         */
        private fun stream(fromIndex: Long): CountingInputStream {
            if (entry.method == ZipArchiveEntry.STORED && fromIndex < entry.size) {
                return CountingInputStream(zipFile.getRawInputStream(entry, fromIndex), fromIndex)
            }

            // Reuse the current stream if it didn't exceed the requested index.
            stream
                ?.takeIf { it.count <= fromIndex }
                ?.let { return it }

            stream?.close()

            return CountingInputStream(zipFile.getInputStream(entry))
                .also { stream = it }
        }

        private var stream: CountingInputStream? = null

        override suspend fun close() {
            tryOrLog {
                withContext(Dispatchers.IO) {
                    stream?.close()
                }
            }
        }
    }

    override val entries: Set<Url> =
        zipFile.entries.toList()
            .filterNot { it.isDirectory }
            .mapNotNull { entry -> Url.fromDecodedPath(entry.name) }
            .toSet()

    override fun get(url: Url): Resource? =
        (url as? RelativeUrl)?.path
            ?.let { zipFile.getEntry(it) }
            ?.takeUnless { it.isDirectory }
            ?.let { Entry(url, it) }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            tryOrLog { zipFile.close() }
        }
    }
}
