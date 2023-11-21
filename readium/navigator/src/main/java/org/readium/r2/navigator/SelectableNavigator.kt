/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator

import android.graphics.RectF
import org.json.JSONObject
import org.readium.r2.navigator.extensions.optRectF
import org.readium.r2.shared.JSONable
import org.readium.r2.shared.publication.Locator
import java.math.BigDecimal

/**
 * A navigator supporting user selection.
 */
interface SelectableNavigator : Navigator {

    /** Currently selected content. */
    suspend fun currentSelection(): Selection?

    /** Clears the current selection. */
    fun clearSelection()
}

/**
 * Represents a user content selection in a navigator.
 *
 * @param locator Location of the user selection in the publication.
 * @param rect Frame of the bounding rect for the selection, in the coordinate of the navigator
 *        view. This is only useful in the context of a VisualNavigator.
 */
data class Selection(
    val locator: Locator,
    val rect: RectF?,
) : JSONable {
    override fun toJSON() = JSONObject().apply {
        val frame = JSONObject()
        val origin = JSONObject()
        val size = JSONObject()
        origin.put("x", rect?.left ?: 0)
        origin.put("y", rect?.top ?: 0)
        size.put("width", rect?.width() ?: 0)
        size.put("height", rect?.height() ?: 0)
        frame.put("origin", origin)
        frame.put("size", size)
        put("location", locator.toJSON())
        put("frame", frame)
    }

    companion object {
        fun fromJSON(json: JSONObject?): Selection? {
            val origin = json?.getJSONObject("frame")?.getJSONObject("origin")
            val size = json?.getJSONObject("frame")?.getJSONObject("size")
            val locator = Locator.fromJSON(json?.getJSONObject("location"))

            if (origin != null && size != null) {
                val x: Float = BigDecimal.valueOf(origin.getDouble("x")).toFloat()
                val y: Float = BigDecimal.valueOf(origin.getDouble("y")).toFloat()
                val width: Float = BigDecimal.valueOf(size.getDouble("width")).toFloat()
                val height: Float = BigDecimal.valueOf(size.getDouble("height")).toFloat()

                return locator?.let {
                    Selection(
                        locator = it,
                        rect = RectF(x, y, x + width, y + height)
                    )
                }
            } else {
                return null
            }
        }
    }
}
