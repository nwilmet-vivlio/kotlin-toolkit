/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.drm

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import java.util.*
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.MessageError
import org.readium.r2.shared.util.Try

abstract class DrmManagementViewModel : ViewModel() {

    abstract val type: String

    open val state: String? = null

    open val provider: String? = null

    open val issued: Date? = null

    open val updated: Date? = null

    open val start: Date? = null

    open val end: Date? = null

    open val copiesLeft: String = "unlimited"

    open val printsLeft: String = "unlimited"

    open val canRenewLoan: Boolean = false

    open suspend fun renewLoan(fragment: Fragment): Try<Date?, Error> =
        Try.failure(MessageError("Renewing a loan is not supported"))

    open val canReturnPublication: Boolean = false

    open suspend fun returnPublication(): Try<Unit, Error> =
        Try.failure(MessageError("Returning a publication is not supported"))
}
