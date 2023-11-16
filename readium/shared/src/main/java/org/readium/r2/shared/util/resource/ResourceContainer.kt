/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.resource

import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ClosedContainer
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError

public typealias ResourceTry<SuccessT> = Try<SuccessT, ReadError>

public typealias ResourceContainer = Container<Resource>

/** A [Container] for a single [Resource]. */
public class SingleResourceContainer(
    private val url: Url,
    private val resource: Resource
) : ClosedContainer<Resource> {

    private class Entry(
        private val resource: Resource
    ) : Resource by resource {

        override suspend fun close() {
            // Do nothing
        }
    }

    override suspend fun entries(): Set<Url> = setOf(url)

    override fun get(url: Url): Resource? {
        if (url.removeFragment().removeQuery() != url) {
            return null
        }

        return Entry(resource)
    }

    override suspend fun close() {
        resource.close()
    }
}
