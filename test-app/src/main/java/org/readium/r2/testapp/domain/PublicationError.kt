/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.domain

import org.readium.r2.shared.publication.protection.ContentProtectionSchemeRetriever
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationFactory

sealed class PublicationError(
    override val message: String,
    override val cause: Error? = null
) : Error {

    class ReadError(override val cause: org.readium.r2.shared.util.data.ReadError) :
        PublicationError(cause.message, cause.cause)

    class UnsupportedScheme(cause: Error) :
        PublicationError(cause.message, cause.cause)

    class UnsupportedContentProtection(cause: Error) :
        PublicationError(cause.message, cause.cause)
    class UnsupportedArchiveFormat(cause: Error) :
        PublicationError(cause.message, cause.cause)

    class UnsupportedPublication(cause: Error) :
        PublicationError(cause.message, cause.cause)

    class InvalidPublication(cause: Error) :
        PublicationError(cause.message, cause.cause)

    class Unexpected(cause: Error) :
        PublicationError(cause.message, cause.cause)

    companion object {

        operator fun invoke(error: AssetRetriever.Error): PublicationError =
            when (error) {
                is AssetRetriever.Error.AccessError ->
                    PublicationError(error)
                is AssetRetriever.Error.ArchiveFormatNotSupported ->
                    UnsupportedArchiveFormat(error)
                is AssetRetriever.Error.SchemeNotSupported ->
                    UnsupportedScheme(error)
            }

        operator fun invoke(error: ContentProtectionSchemeRetriever.Error): PublicationError =
            when (error) {
                is ContentProtectionSchemeRetriever.Error.AccessError ->
                    PublicationError(error)
                ContentProtectionSchemeRetriever.Error.NoContentProtectionFound ->
                    UnsupportedContentProtection(error)
            }

        operator fun invoke(error: PublicationFactory.Error): PublicationError =
            when (error) {
                is PublicationFactory.Error.ReadError ->
                    PublicationError(error)
                is PublicationFactory.Error.UnsupportedAsset ->
                    UnsupportedPublication(error)
                is PublicationFactory.Error.UnsupportedContentProtection ->
                    UnsupportedContentProtection(error)
            }
    }
}
