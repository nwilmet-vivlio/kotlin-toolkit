/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.resource.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Error as SharedError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.ResourceError
import org.readium.r2.shared.util.resource.ResourceTry
import org.readium.r2.shared.util.resource.readAsString

/**
 * Extracts pure content from a marked-up (e.g. HTML) or binary (e.g. PDF) resource.
 */
@ExperimentalReadiumApi
public interface ResourceContentExtractor {

    /**
     * Extracts the text content of the given [resource].
     */
    public suspend fun extractText(resource: Resource): ResourceTry<String> = Try.success("")

    public interface Factory {
        /**
         * Creates a [ResourceContentExtractor] instance for the given [resource].
         *
         * Return null if the resource format is not supported.
         */
        public suspend fun createExtractor(resource: Resource): ResourceContentExtractor?
    }

    public sealed class Error(
        public override val message: String
    ) : SharedError {

        public class Resource(
            override val cause: ResourceError?
        ) : Error("An error occurred while attempting to read the resource.")

        public class Content(
            override val cause: org.readium.r2.shared.util.Error?
        ) : Error("Resource content doesn't match what was expected.")
    }
}

@ExperimentalReadiumApi
public class DefaultResourceContentExtractorFactory : ResourceContentExtractor.Factory {

    override suspend fun createExtractor(resource: Resource): ResourceContentExtractor? =
        when (resource.mediaType().getOrNull()) {
            MediaType.HTML, MediaType.XHTML -> HtmlResourceContentExtractor()
            else -> null
        }
}

/**
 * [ResourceContentExtractor] implementation for HTML resources.
 */
@ExperimentalReadiumApi
public class HtmlResourceContentExtractor : ResourceContentExtractor {

    override suspend fun extractText(resource: Resource): ResourceTry<String> = withContext(
        Dispatchers.IO
    ) {
        resource
            .readAsString()
            .map { html ->
                val body = Jsoup.parse(html).body().text()
                // Transform HTML entities into their actual characters.
                Parser.unescapeEntities(body, false)
            }
    }
}
