/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.http

import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ClosedContainer
import org.readium.r2.shared.util.resource.ResourceEntry
import org.readium.r2.shared.util.resource.toResourceEntry

/**
 * Fetches remote resources through HTTP.
 *
 * Since this container is used when doing progressive download streaming (e.g. audiobook), the HTTP
 * byte range requests are open-ended and reused. This helps to avoid issuing too many requests.
 *
 * @param client HTTP client used to perform HTTP requests.
 * @param baseUrl Base URL from which relative URLs are served.
 */
public class HttpContainer(
    private val client: HttpClient,
    private val baseUrl: Url? = null,
    private val entries: Set<Url>
) : ClosedContainer<ResourceEntry> {

    override suspend fun entries(): Set<Url> = entries

    override fun get(url: Url): ResourceEntry? {
        val absoluteUrl = (baseUrl?.resolve(url) ?: url) as? AbsoluteUrl

        return if (absoluteUrl == null || !absoluteUrl.isHttp) {
            null
        } else {
            HttpResource(client, absoluteUrl).toResourceEntry(url)
        }
    }

    override suspend fun close() {}
}
