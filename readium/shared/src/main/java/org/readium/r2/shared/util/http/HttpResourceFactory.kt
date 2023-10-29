/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.http

import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.ResourceFactory

public class HttpResourceFactory(
    private val httpClient: HttpClient
) : ResourceFactory {

    override suspend fun create(
        url: AbsoluteUrl,
        mediaType: MediaType?
    ): Try<Resource, ResourceFactory.Error> {
        if (!url.isHttp) {
            return Try.failure(ResourceFactory.Error.SchemeNotSupported(url.scheme))
        }

        val resource = HttpResource(httpClient, url)
        return Try.success(resource)
    }
}
