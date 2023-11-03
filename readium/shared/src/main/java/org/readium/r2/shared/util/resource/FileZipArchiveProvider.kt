/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.resource

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.util.FilesystemError
import org.readium.r2.shared.util.MessageError
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.mediatype.MediaTypeHints
import org.readium.r2.shared.util.mediatype.MediaTypeRetriever
import org.readium.r2.shared.util.mediatype.MediaTypeSnifferContentError
import org.readium.r2.shared.util.mediatype.MediaTypeSnifferError
import org.readium.r2.shared.util.mediatype.ResourceMediaTypeSnifferContent

/**
 * An [ArchiveFactory] to open local ZIP files with Java's [ZipFile].
 */
public class FileZipArchiveProvider(
    private val mediaTypeRetriever: MediaTypeRetriever
) : ArchiveProvider {

    override fun sniffHints(hints: MediaTypeHints): Try<MediaType, MediaTypeSnifferError.NotRecognized> {
        if (hints.hasMediaType("application/zip") ||
            hints.hasFileExtension("zip")
        ) {
            return Try.success(MediaType.ZIP)
        }

        return Try.failure(MediaTypeSnifferError.NotRecognized)
    }

    override suspend fun sniffResource(resource: ResourceMediaTypeSnifferContent): Try<MediaType, MediaTypeSnifferError> {
        val file = resource.source?.toFile()
            ?: return Try.Failure(MediaTypeSnifferError.NotRecognized)

        return withContext(Dispatchers.IO) {
            try {
                JavaZipContainer(ZipFile(file), file, mediaTypeRetriever)
                Try.success(MediaType.ZIP)
            } catch (e: ZipException) {
                Try.failure(MediaTypeSnifferError.NotRecognized)
            } catch (e: SecurityException) {
                Try.failure(
                    MediaTypeSnifferError.SourceError(
                        MediaTypeSnifferContentError.Forbidden(ThrowableError(e))
                    )
                )
            } catch (e: IOException) {
                Try.failure(
                    MediaTypeSnifferError.SourceError(
                        MediaTypeSnifferContentError.Filesystem(FilesystemError(e))
                    )
                )
            } catch (e: Exception) {
                Try.failure(
                    MediaTypeSnifferError.SourceError(
                        MediaTypeSnifferContentError.Unknown(ThrowableError(e))
                    )
                )
            }
        }
    }

    override suspend fun create(
        resource: Resource,
        password: String?
    ): Try<Container, ArchiveFactory.Error> {
        if (password != null) {
            return Try.failure(ArchiveFactory.Error.PasswordsNotSupported())
        }

        val file = resource.source?.toFile()
            ?: return Try.Failure(
                ArchiveFactory.Error.UnsupportedFormat(
                    MessageError("Resource not supported because file cannot be directly accessed.")
                )
            )

        val container = open(file)
            .getOrElse { return Try.failure(it) }

        return Try.success(container)
    }

    // Internal for testing purpose
    internal suspend fun open(file: File): Try<Container, ArchiveFactory.Error> =
        withContext(Dispatchers.IO) {
            try {
                val archive = JavaZipContainer(ZipFile(file), file, mediaTypeRetriever)
                Try.success(archive)
            } catch (e: ZipException) {
                Try.failure(ArchiveFactory.Error.ResourceError(ResourceError.InvalidContent(e)))
            } catch (e: SecurityException) {
                Try.failure(ArchiveFactory.Error.ResourceError(ResourceError.Forbidden(e)))
            } catch (e: IOException) {
                Try.failure(ArchiveFactory.Error.ResourceError(ResourceError.Filesystem(e)))
            }
        }
}
