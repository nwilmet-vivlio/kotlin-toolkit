/*
 * Module: r2-shared-kotlin
 * Developers: Aferdita Muriqi, Mostapha Idoubihi, Paul Stoica
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.shared


import org.joda.time.DateTime
import org.json.JSONObject
import java.net.URI

/**
 * Locator : That class is used to define a precise location in a Publication
 *
 * @var publicationId: String - Identifier of a Publication
 * @var spineIndex: Integer - Index at a spine element
 * @var spineHref: String? - ( Optional ) String reference to the spine element
 * @var created: DateTime - Date when the Locator has been created
 * @var title: URI - Title of the spine element
 * @var locations: Location - Object that's used to locate the target
 *
 *
 * @var text: LocatorText? - ( Optional ) Describe the Locator's context
 *
 */
class Locator(val publicationId: String, val spineIndex: Int, val spineHref: String?, val title: URI, val locations: Location): JSONable {

    var created: DateTime = DateTime.now()
    var text = LocatorText(null, null, null)

    override fun getJSON(): JSONObject {
        val json = JSONObject()
        json.putOpt("publicationId", publicationId)
        json.putOpt("spineIndex", spineIndex)
        json.putOpt("spineHref", spineHref)
        json.putOpt("created", created)
        json.putOpt("title", title)
        json.putOpt("location", locations.toString())
        json.putOpt("text", text)
        return json
    }

    override fun toString(): String{
        return """{ "publicationId": "$publicationId", "spineIndex": "$spineIndex",  "spineHref": "$spineHref", "created": "$created", "title": "$title", "locations" : ${locations}  "text" : "${text}" """
    }

    fun setText(before: String? = null, highlight: String? = null, after: String? = null){
        text.before = before
        text.highlight = highlight
        text.after = after
    }

    inner class LocatorText(var before: String?, var highlight: String?, var after: String?): JSONable{

        override fun getJSON(): JSONObject {
            val json = JSONObject()
            json.putOpt("before", before)
            json.putOpt("highlight", highlight)
            json.putOpt("after", after)
            return json
        }

        override fun toString(): String{
            var jsonString =  """{"""
            before.let { jsonString += """, "before": "$before" """ }
            highlight.let { jsonString += """, "highlight": "$highlight" """ }
            after.let { jsonString += """ "after": "$after" """ }
            jsonString += """}"""
            return jsonString
        }
    }
}

/**
 * Location : Class that contain the different variables needed to localize a particular position
 *
 * @var pubId: String - Identifier of a Publication
 * @var cfi: String? - String formatted to designed a particular place in an EPUB
 * @var css: String? - Css selector
 * @var progression: Float - A percentage ( between 0 and 1 ) of the progression in a Publication
 * @var position: integer - Index of a segment in the resource.
 *
 */
class Location(val pubId: String, val cfi: String?, val css: String?, val progression: Float, val position: Int): JSONable{

    init {
        if (position < 0 || !(progression in 0..1)) {
            throw Throwable("Error : invalid arguments.")
        }
    }

    override fun getJSON(): JSONObject {
        val json = JSONObject()
        json.putOpt("pubId", pubId)
        if (cfi != null) {
            json.putOpt("cfi", cfi)
        }
        if (css != null) {
            json.putOpt("css", css)
        }
        json.putOpt("progression", progression)
        json.putOpt("position", position)
        return json
    }

    override fun toString(): String {
        var jsonString = """{"""
        pubId.let { jsonString += """ "id": "$pubId" """ }
        cfi.let { jsonString += """, "cfi": "$cfi" """ }
        css.let { jsonString += """, "css": "$css" """ }
        progression.let { jsonString += """, "progression": "$progression" """ }
        position.let { jsonString += """, "position": "$position" """ }
        jsonString += """}"""
        return jsonString
    }
}