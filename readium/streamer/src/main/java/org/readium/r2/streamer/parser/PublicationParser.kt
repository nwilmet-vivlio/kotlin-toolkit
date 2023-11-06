/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.streamer.parser

import kotlin.String
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.PublicationContainer
import org.readium.r2.shared.util.Error as BaseError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.logging.WarningLogger
import org.readium.r2.shared.util.mediatype.MediaType

/**
 *  Parses a Publication from an asset.
 */
public interface PublicationParser {

    /**
     * Full publication asset.
     *
     * @param mediaType Media type of the "virtual" publication asset, built from the source asset.
     * For example, if the source asset was a `application/audiobook+json`, the "virtual" asset
     * media type will be `application/audiobook+zip`.
     * @param container Container granting access to the resources of the publication.
     */
    public data class Asset(
        val mediaType: MediaType,
        val container: PublicationContainer
    )

    /**
     * Constructs a [Publication.Builder] to build a [Publication] from a publication asset.
     *
     * @param asset Publication asset.
     * @param warnings Used to report non-fatal parsing warnings, such as publication authoring
     * mistakes. This is useful to warn users of potential rendering issues or help authors
     * debug their publications.
     */
    public suspend fun parse(
        asset: Asset,
        warnings: WarningLogger? = null
    ): Try<Publication.Builder, Error>

    public sealed class Error(
        public override val message: String,
        public override val cause: org.readium.r2.shared.util.Error?
    ) : BaseError {

        public class UnsupportedFormat :
            Error("Asset format not supported.", null)

        public class ReadError(override val cause: org.readium.r2.shared.util.data.ReadError) :
            Error("An error occurred while trying to read asset.", cause)
    }
}
