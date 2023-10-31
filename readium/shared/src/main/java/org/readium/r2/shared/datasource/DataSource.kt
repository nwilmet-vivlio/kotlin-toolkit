/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.datasource

import org.readium.r2.shared.util.SuspendingCloseable
import org.readium.r2.shared.util.Try

/**
 * Acts as a proxy to an actual data source by handling read access.
 */
internal interface DataSource<E> : SuspendingCloseable {

    /**
     * Returns data length from metadata if available, or calculated from reading the bytes otherwise.
     *
     * This value must be treated as a hint, as it might not reflect the actual bytes length. To get
     * the real length, you need to read the whole resource.
     */
    suspend fun length(): Try<Long, E>

    /**
     * Reads the bytes at the given range.
     *
     * When [range] is null, the whole content is returned. Out-of-range indexes are clamped to the
     * available length automatically.
     */
    suspend fun read(range: LongRange? = null): Try<ByteArray, E>
}
