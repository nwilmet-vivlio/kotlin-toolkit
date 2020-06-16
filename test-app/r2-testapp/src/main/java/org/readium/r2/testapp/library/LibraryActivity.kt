/*
 * Module: r2-testapp-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann, Mostapha Idoubihi, Paul Stoica
 *
 * Copyright (c) 2018. European Digital Reading Lab. All rights reserved.
 * Licensed to the Readium Foundation under one or more contributor license agreements.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.testapp.library


import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.webkit.URLUtil
import android.widget.Button
import android.widget.EditText
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kittinunf.fuel.Fuel
import com.mcxiaoke.koi.ext.close
import com.mcxiaoke.koi.ext.onClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.task
import nl.komponents.kovenant.then
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.jetbrains.anko.*
import org.jetbrains.anko.appcompat.v7.Appcompat
import org.jetbrains.anko.design.*
import org.jetbrains.anko.recyclerview.v7.recyclerView
import org.json.JSONObject
import org.readium.r2.opds.OPDS1Parser
import org.readium.r2.opds.OPDS2Parser
import org.readium.r2.shared.Injectable
import org.readium.r2.shared.drm.DRM
import org.readium.r2.shared.extensions.putPublication
import org.readium.r2.shared.extensions.tryOrNull
import org.readium.r2.shared.format.Format
import org.readium.r2.shared.opds.ParseData
import org.readium.r2.shared.promise
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.pageList
import org.readium.r2.shared.publication.opds.images
import org.readium.r2.streamer.parser.PubBox
import org.readium.r2.streamer.server.Server
import org.readium.r2.testapp.BuildConfig.DEBUG
import org.readium.r2.testapp.R
import org.readium.r2.testapp.R2AboutActivity
import org.readium.r2.testapp.audiobook.AudiobookActivity
import org.readium.r2.testapp.comic.ComicActivity
import org.readium.r2.testapp.comic.DiViNaActivity
import org.readium.r2.testapp.db.*
import org.readium.r2.testapp.drm.LCPLibraryActivityService
import org.readium.r2.testapp.epub.EpubActivity
import org.readium.r2.testapp.epub.R2SyntheticPageList
import org.readium.r2.testapp.opds.GridAutoFitLayoutManager
import org.readium.r2.testapp.opds.OPDSDownloader
import org.readium.r2.testapp.opds.OPDSListActivity
import org.readium.r2.testapp.permissions.PermissionHelper
import org.readium.r2.testapp.permissions.Permissions
import org.readium.r2.testapp.utils.ContentResolverUtil
import org.readium.r2.testapp.utils.R2IntentHelper
import org.readium.r2.testapp.utils.extension
import org.readium.r2.testapp.utils.extensions.authorName
import org.readium.r2.testapp.utils.extensions.format
import org.readium.r2.testapp.utils.extensions.parse
import org.readium.r2.testapp.utils.toFile
import org.zeroturnaround.zip.ZipUtil
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

var activitiesLaunched: AtomicInteger = AtomicInteger(0)

@SuppressLint("Registered")
open class LibraryActivity : AppCompatActivity(), BooksAdapter.RecyclerViewClickListener, LCPLibraryActivityService, CoroutineScope {


    /**
     * Context of this scope.
     */
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main

    protected lateinit var server: Server
    private var localPort: Int = 0

    private lateinit var booksAdapter: BooksAdapter
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var permissions: Permissions
    private lateinit var preferences: SharedPreferences
    private lateinit var R2DIRECTORY: String

    private lateinit var database: BooksDatabase
    private lateinit var opdsDownloader: OPDSDownloader

    private lateinit var positionsDB: PositionsDatabase

    protected lateinit var catalogView: androidx.recyclerview.widget.RecyclerView
    private lateinit var alertDialog: AlertDialog

    protected var listener: LibraryActivity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = getSharedPreferences("org.readium.r2.settings", Context.MODE_PRIVATE)

        val s = ServerSocket(if (DEBUG) 8080 else 0)
        s.localPort
        s.close()

        localPort = s.localPort
        server = Server(localPort, applicationContext)

        val properties = Properties()
        val inputStream = this.assets.open("configs/config.properties")
        properties.load(inputStream)
        val useExternalFileDir = properties.getProperty("useExternalFileDir", "false")!!.toBoolean()

        R2DIRECTORY = if (useExternalFileDir) {
            this.getExternalFilesDir(null)?.path + "/"
        } else {
            this.filesDir.path + "/"
        }

        permissions = Permissions(this)
        permissionHelper = PermissionHelper(this, permissions)

        opdsDownloader = OPDSDownloader(this)
        database = BooksDatabase(this)
        books = database.books.list()

        positionsDB = PositionsDatabase(this)

        booksAdapter = BooksAdapter(this, books, this)

        parseIntent(null)


        coordinatorLayout {
            lparams {
                topMargin = dip(8)
                bottomMargin = dip(8)
                padding = dip(0)
                width = matchParent
                height = matchParent
            }

            catalogView = recyclerView {
                layoutManager = GridAutoFitLayoutManager(this@LibraryActivity, 120)
                adapter = booksAdapter

                lparams {
                    elevation = 2F
                    width = matchParent
                }

                addItemDecoration(VerticalSpaceItemDecoration(10))

            }

            floatingActionButton {
                imageResource = R.drawable.icon_plus_white
                contentDescription = context.getString(R.string.floating_button_add_book)

                onClick {

                    alertDialog = alert(Appcompat, context.getString(R.string.add_publication_to_library)) {
                        customView {
                            verticalLayout {
                                lparams {
                                    bottomPadding = dip(16)
                                }
                                button {
                                    text = context.getString(R.string.select_from_your_device)
                                    onClick {
                                        alertDialog.dismiss()
                                        showDocumentPicker()
                                    }
                                }
                                button {
                                    text = context.getString(R.string.download_from_url)
                                    onClick {
                                        alertDialog.dismiss()
                                        showDownloadFromUrlAlert()
                                    }
                                }
                            }
                        }
                    }.show()
                }
            }.lparams {
                gravity = Gravity.END or Gravity.BOTTOM
                margin = dip(16)
            }
        }

    }

    override fun onStart() {
        super.onStart()

        startServer()

        permissionHelper.storagePermission {
            if (books.isEmpty()) {
                if (!preferences.contains("samples")) {
                    val dir = File(R2DIRECTORY)
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    copySamplesFromAssetsToStorage()
                    preferences.edit().putBoolean("samples", true).apply()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        booksAdapter.notifyDataSetChanged()
    }


    override fun onDestroy() {
        super.onDestroy()
        //TODO not sure if this is needed
        stopServer()
    }

    open fun showDownloadFromUrlAlert() {
        var editTextHref: EditText? = null
        alert(Appcompat, "Add a publication from URL") {

            customView {
                verticalLayout {
                    textInputLayout {
                        padding = dip(10)
                        editTextHref = editText {
                            hint = "URL"
                            contentDescription = "Enter A URL"
                        }
                    }
                }
            }
            positiveButton("Add") { }
            negativeButton("Cancel") { }

        }.build().apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setOnShowListener {
                val b = getButton(AlertDialog.BUTTON_POSITIVE)
                b.setOnClickListener {
                    if (TextUtils.isEmpty(editTextHref!!.text)) {
                        editTextHref!!.error = "Please Enter A URL."
                        editTextHref!!.requestFocus()
                    } else if (!URLUtil.isValidUrl(editTextHref!!.text.toString())) {
                        editTextHref!!.error = "Please Enter A Valid URL."
                        editTextHref!!.requestFocus()
                    } else {
                        val url = tryOrNull { URL(editTextHref?.text.toString()) }
                            ?: return@setOnClickListener

                        // FIXME: We should download the content first to determine what to do with it.
                        val format = Format.of(fileExtension = url.extension)
                        when (format) {
                            Format.LCP_LICENSE -> {
                                dismiss()
                                parseIntentLcpl(url.toString(), isNetworkAvailable)
                            }
                            Format.EPUB, Format.CBZ -> {
                                dismiss()
                                parseIntentPublication(url.toString())
                            }
                            Format.READIUM_AUDIOBOOK, Format.READIUM_AUDIOBOOK_MANIFEST, Format.DIVINA, Format.DIVINA_MANIFEST -> {
                                editTextHref!!.error = "Import ${format.name} via URL not supported yet."
                                editTextHref!!.requestFocus()
                            }
                            else -> {
                                val parseDataPromise = parseURL(url)
                                parseDataPromise.successUi { parseData ->
                                    if (parseData.feed == null) {
                                        dismiss()
                                        downloadData(parseData)
                                    } else {
                                        editTextHref!!.error = "Please Enter A Valid Publication URL."
                                        editTextHref!!.requestFocus()
                                    }
                                }
                                parseDataPromise.failUi {
                                    editTextHref!!.error = "Please Enter A Valid Publication URL."
                                    editTextHref!!.requestFocus()
                                }
                            }
                        }
                    }
                }
            }

        }.show()
    }

    private fun downloadData(parseData: ParseData) {
        val progress = indeterminateProgressDialog(getString(R.string.progress_wait_while_downloading_book))
        progress.show()

        val publication = parseData.publication ?: return

        if (publication.type == Publication.TYPE.EPUB) {

            val downloadUrl = getDownloadURL(publication)

            opdsDownloader.publicationUrl(downloadUrl.toString()).successUi { pair ->

                val publicationIdentifier = publication.metadata.identifier!!
                val author = publication.metadata.authorName
                task {
                    getBitmapFromURL(publication.images.first().href)
                }.then {
                    val bitmap = it
                    val stream = ByteArrayOutputStream()
                    bitmap?.compress(Bitmap.CompressFormat.PNG, 100, stream)

                    val book = Book(title = publication.metadata.title, author = author, href = pair.first, identifier = publicationIdentifier, cover = stream.toByteArray(), ext = Publication.EXTENSION.EPUB.value, progression = "{}")

                    launch {
                        progress.dismiss()
                        database.books.insert(book, false)?.let { id ->
                            book.id = id
                            books.add(0, book)
                            booksAdapter.notifyDataSetChanged()
                            catalogView.longSnackbar("publication added to your library")
                            //prepareSyntheticPageList(publication, book)
                        } ?: run {

                            showDuplicateBookAlert(book, publication, false)

                        }
                    }
                }.fail {
                    launch {
                        progress.dismiss()
                        catalogView.snackbar("$it")
                    }
                }
            }
        } else if (publication.type == Publication.TYPE.WEBPUB || publication.type == Publication.TYPE.AUDIO) {

            val self = publication.linkWithRel("self")

            when (publication.type) {
                Publication.TYPE.WEBPUB -> {
                    progress.dismiss()
                    prepareWebPublication(self?.href!!, webPub = null, add = true)
                }
                Publication.TYPE.AUDIO -> {
                    progress.dismiss()
                    prepareWebPublication(self?.href!!, webPub = null, add = true)
                }
                else -> {
                    progress.dismiss()
                    catalogView.snackbar("Invalid publication")
                }
            }

        }
    }

    private fun showDuplicateBookAlert(book: Book, publication: Publication, lcp: Boolean) {
        val duplicateAlert = alert(Appcompat, "Publication already exists") {

            positiveButton("Add anyway") { }
            negativeButton("Cancel") { }

        }.build()
        duplicateAlert.apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setOnShowListener {
                val button = getButton(AlertDialog.BUTTON_POSITIVE)
                button.setOnClickListener {
                    database.books.insert(book, true)?.let {
                        book.id = it
                        books.add(0, book)
                        duplicateAlert.dismiss()
                        booksAdapter.notifyDataSetChanged()
                        catalogView.longSnackbar("publication added to your library")
//                        if (!lcp) {
                        //prepareSyntheticPageList(publication, book)
//                        }
                    }
                }
                val cancelButton = getButton(AlertDialog.BUTTON_NEGATIVE)
                cancelButton.setOnClickListener {
                    File(book.href).delete()
                    duplicateAlert.dismiss()
                }
            }
        }
        duplicateAlert.show()
    }

    private fun showDocumentPicker() {
        // ACTION_GET_DOCUMENT allows to import a system file by creating a copy of it
        // with access to every app that manages files
        val intent = Intent(Intent.ACTION_GET_CONTENT)

        // Filter to only show results that can be "opened", such as a
        // file (as opposed to a list of contacts or timezones)
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // Filter to show only epubs, using the image MIME data type.
        // To search for all documents available via installed storage providers,
        // it would be "*/*".
        intent.type = "*/*"
//        val mimeTypes = arrayOf(
//                "application/epub+zip",
//                "application/x-cbz"
//        )
//        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)

        startActivityForResult(intent, 1)
    }

    private fun parseURL(url: URL): Promise<ParseData, Exception> {
        return Fuel.get(url.toString(), null).promise() then {
            val (_, _, result) = it
            if (isJson(result)) {
                OPDS2Parser.parse(result, url)
            } else {
                OPDS1Parser.parse(result, url)
            }
        }
    }

    private fun isJson(byteArray: ByteArray): Boolean {
        return try {
            JSONObject(String(byteArray))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getPublicationURL(src: String): JSONObject? {
        return try {
            val url = URL(src.removeSuffix("/"))
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.doInput = true
            connection.connect()

            val jsonManifestURL = URL(connection.getHeaderField("Location")
                    ?: src.removeSuffix("/")).openConnection()
            jsonManifestURL.connect()

            val jsonManifest = jsonManifestURL.getInputStream().readBytes()
            val stringManifest = jsonManifest.toString(Charset.defaultCharset())
            val json = JSONObject(stringManifest)

            jsonManifestURL.close()
            connection.disconnect()
            connection.close()

            json
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun getBitmapFromURL(src: String): Bitmap? {
        return try {
            val url = URL(src)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            val input = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(input)
            connection.close()
            bitmap
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun getDownloadURL(publication: Publication): URL? {
        var url: URL? = null
        val links = publication.links
        for (link in links) {
            val href = link.href
            if (href.contains(Publication.EXTENSION.EPUB.value) || href.contains(Publication.EXTENSION.LCPL.value)) {
                url = URL(href)
                break
            }
        }
        return url
    }

    private fun parseIntent(filePath: String?) {

        filePath?.let {

            val progress = indeterminateProgressDialog(getString(R.string.progress_wait_while_downloading_book))
            progress.show()

            val fileName = UUID.randomUUID().toString()
            val publicationPath = R2DIRECTORY + fileName

            task {
                ContentResolverUtil.copyFile(File(filePath), File(publicationPath))
            } then {
                preparePublication(publicationPath, filePath, fileName, progress)
            }

        } ?: run {
            val intent = intent
            val uriString: String? = intent.getStringExtra(R2IntentHelper.URI)
            uriString?.let {
                when (Publication.EXTENSION.fromString(intent.getStringExtra(R2IntentHelper.EXTENSION)!!)) {
                    Publication.EXTENSION.LCPL -> parseIntentLcpl(uriString, isNetworkAvailable)
                    else -> parseIntentPublication(uriString)
                }
            }
        }
    }

    private fun parseIntentPublication(uriString: String) {
        val uri: Uri? = Uri.parse(uriString)
        if (uri != null) {

            val progress = indeterminateProgressDialog(getString(R.string.progress_wait_while_downloading_book))
            progress.show()
            val fileName = UUID.randomUUID().toString()
            val publicationPath = R2DIRECTORY + fileName

            task {
                ContentResolverUtil.getContentInputStream(this, uri, publicationPath)
            } then {
                preparePublication(publicationPath, uriString, fileName, progress)
            }

        }
    }


    private fun preparePublication(publicationPath: String, uriString: String, fileName: String, progress: ProgressDialog) {

        val file = File(publicationPath)

        try {
            launch {
                val pub = Publication.parse(publicationPath)
                if (pub != null) {
                    prepareToServe(pub, fileName, file.absolutePath, add = true, lcp = pub.container.drm?.let { true }
                            ?: false)
                    progress.dismiss()
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun prepareSyntheticPageList(pub: Publication, book: Book) {
        if (pub.pageList.isEmpty() && !(positionsDB.positions.isInitialized(book.id!!))) {
            val syntheticPageList = R2SyntheticPageList(positionsDB, book.id!!, pub.metadata.identifier!!)

            when (pub.type) {
                Publication.TYPE.EPUB -> syntheticPageList.execute(Triple(localPort, book.fileName!!, pub.readingOrder))
                Publication.TYPE.WEBPUB -> syntheticPageList.execute(Triple(0, book.fileName!!, pub.readingOrder))
                else -> {
                    //no page list
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {

            R.id.opds -> {
                startActivity(intentFor<OPDSListActivity>())
                false
            }
            R.id.about -> {
                startActivity(intentFor<R2AboutActivity>())
                false
            }

            else -> super.onOptionsItemSelected(item)
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        this.permissions.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startServer() {
        if (!server.isAlive) {
            try {
                server.start()
            } catch (e: IOException) {
                // do nothing
                if (DEBUG) Timber.e(e)
            }
            if (server.isAlive) {
//                // Add your own resources here
//                server.loadCustomResource(assets.open("scripts/test.js"), "test.js")
//                server.loadCustomResource(assets.open("styles/test.css"), "test.css")
//                server.loadCustomFont(assets.open("fonts/test.otf"), applicationContext, "test.otf")

                server.loadCustomResource(assets.open("Search/mark.js"), "mark.js", Injectable.Script)
                server.loadCustomResource(assets.open("Search/search.js"), "search.js", Injectable.Script)
                server.loadCustomResource(assets.open("Search/mark.css"), "mark.css", Injectable.Style)

                isServerStarted = true
            }
        }
    }

    private fun stopServer() {
        if (server.isAlive) {
            server.stop()
            isServerStarted = false
        }
    }

    private fun copySamplesFromAssetsToStorage() {
        assets.list("Samples")?.filter {
            it.endsWith(Publication.EXTENSION.EPUB.value)
                    || it.endsWith(Publication.EXTENSION.CBZ.value)
                    || it.endsWith(Publication.EXTENSION.AUDIO.value)
                    || it.endsWith(Publication.EXTENSION.DIVINA.value)
        }?.let { list ->
            for (element in list) {
                val input = assets.open("Samples/$element")
                val fileName = UUID.randomUUID().toString()
                val publicationPath = R2DIRECTORY + fileName

                when {
                    element.endsWith(Publication.EXTENSION.DIVINA.value) -> {
                        val output = File(publicationPath)
                        if (!output.exists()) {
                            if (!output.mkdir()) {
                                throw RuntimeException("Cannot create directory")
                            }
                        }
                        ZipUtil.unpack(input, output)
                    }
                    element.endsWith(Publication.EXTENSION.AUDIO.value) -> {
                        val output = File(publicationPath)
                        if (!output.exists()) {
                            if (!output.mkdir()) {
                                throw RuntimeException("Cannot create directory")
                            }
                        }
                        ZipUtil.unpack(input, output)
                    }
                    else -> input.toFile(publicationPath)
                }

                val file = File(publicationPath)

                val pub = Publication.parse(publicationPath)
                if (pub != null) {
                    prepareToServe(pub, fileName, file.absolutePath, add = true, lcp = pub.container.drm?.brand == DRM.Brand.lcp)
                }
            }
        }
    }

    protected fun prepareToServe(pub: PubBox?, fileName: String, absolutePath: String, add: Boolean, lcp: Boolean) {
        val format = runBlocking { Format.ofFile(absolutePath) } ?: pub?.publication?.type?.format
        if (pub == null || format == null) {
            catalogView.snackbar("Invalid publication")
            return
        }
        val publication = pub.publication
        val container = pub.container

        launch {
            val key = publication.metadata.identifier ?: publication.metadata.title
            preferences.edit().putString("$key-publicationPort", localPort.toString()).apply()
            server.addEpub(publication, container, "/$fileName", applicationContext.filesDir.path + "/" + Injectable.Style.rawValue + "/UserProperties.json")

            if (add) {
                val publicationIdentifier = publication.metadata.identifier ?: ""
                val author = publication.metadata.authorName
                val cover = publication.coverLink?.href?.let {
                    try {
                        container.data(it)
                    } catch (e: Exception) {
                        Timber.e(e)
                        null
                    }
                }
                val book = Book(title = publication.metadata.title, author = author, href = absolutePath, identifier = publicationIdentifier, cover = cover, ext = ".${format.fileExtension}", progression = "{}")

                database.books.insert(book, false)?.let { id ->
                    book.id = id
                    books.add(0, book)
                    booksAdapter.notifyDataSetChanged()
                    catalogView.longSnackbar("publication added to your library")
                } ?: run {
                    showDuplicateBookAlert(book, publication, lcp)
                }
            }
        }
    }

    override fun recyclerViewListLongClicked(v: View, position: Int) {
        val layout = LayoutInflater.from(this).inflate(R.layout.popup_delete, catalogView, false) //Inflating the layout
        val popup = PopupWindow(this)
        popup.contentView = layout
        popup.width = ListPopupWindow.WRAP_CONTENT
        popup.height = ListPopupWindow.WRAP_CONTENT
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.showAsDropDown(v, 24, -350, Gravity.CENTER)
        val delete: Button = layout.findViewById(R.id.delete) as Button
        delete.setOnClickListener {
            val book = books[position]
            val publicationPath = R2DIRECTORY + book.fileName
            books.remove(book)
            booksAdapter.notifyDataSetChanged()
            catalogView.longSnackbar("publication deleted from your library")
            val file = File(publicationPath)
            file.delete()
            popup.dismiss()
            val deleted = database.books.delete(book)
            if (deleted > 0) {
                BookmarksDatabase(this).bookmarks.delete(deleted.toLong())
                PositionsDatabase(this).positions.delete(deleted.toLong())
            }
        }
    }

    private val isNetworkAvailable: Boolean
        get() {
            val connectivityManager: ConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetworkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }

    override fun recyclerViewListClicked(v: View, position: Int) {
        val progress = indeterminateProgressDialog(getString(R.string.progress_wait_while_preparing_book))
        progress.show()
        task {
            val book = books[position]
            val publicationPath = R2DIRECTORY + book.fileName
            val file = File(book.href)

            if (book.ext == Publication.EXTENSION.JSON.value) {
                // FIXME: Use proper parser
                prepareWebPublication(book.href, book, add = false)

            } else {
                val pub = Publication.parse(book.href, fileExtension = book.ext.removePrefix("."))
                if (pub != null) {
                    if (pub.container.drm?.brand == DRM.Brand.lcp) {
                        prepareAndStartActivityWithLCP(pub.container.drm!!, pub, book, file, publicationPath, pub.publication, isNetworkAvailable)
                    } else {
                        val coverByteArray: ByteArray? = null
                        // FIXME: We shouldn't do that because if the cover is too large, it will crash the app with a TransactionTooLargeException
//                        val coverByteArray = pub.publication.coverLink?.href?.let {
//                            try {
//                                pub.container.data(it)
//                            } catch (e: Exception) {
//                                null
//                            }
//                        }
                        prepareAndStartActivity(pub, book, file, publicationPath, pub.publication, coverByteArray)
                    }
                }
            }
        } then {
            progress.dismiss()
        } fail {
            progress.dismiss()
        }
    }

    private fun prepareWebPublication(externalManifest: String, webPub: Book?, add: Boolean) {
        task {

            getPublicationURL(externalManifest)

        } then { json ->

            json?.let {
                val externalPub = Publication.fromJSON(json)
                    ?: throw Exception("Invalid Publication")
                val externalURIBase = externalPub.linkWithRel("self")!!.href.substring(0, externalManifest.lastIndexOf("/") + 1)
                val externalURIFileName = externalPub.linkWithRel("self")!!.href.substring(externalManifest.lastIndexOf("/") + 1)

                var book: Book? = null

                if (add) {

                    externalPub.coverLink?.href?.let { href ->
                        val bitmap: Bitmap? = if (URI(href).isAbsolute) {
                            getBitmapFromURL(href)
                        } else {
                            getBitmapFromURL(externalURIBase + href)
                        }
                        val stream = ByteArrayOutputStream()
                        bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, stream)

                        book = Book(title = externalPub.metadata.title, href = externalManifest, identifier = externalPub.metadata.identifier!!, cover = stream.toByteArray(), ext = Publication.EXTENSION.JSON.value)

                    } ?: run {
                        book = Book(title = externalPub.metadata.title, href = externalManifest, identifier = externalPub.metadata.identifier!!, ext = Publication.EXTENSION.JSON.value)
                    }

                    launch {
                        database.books.insert(book!!, false)?.let { id ->
                            book!!.id = id
                            books.add(0, book!!)
                            booksAdapter.notifyDataSetChanged()
                            catalogView.longSnackbar("publication added to your library")
                            //prepareSyntheticPageList(externalPub, book!!)
                        } ?: run {
                            showDuplicateBookAlert(book!!, externalPub, false)
                        }
                    }
                } else {
                    book = webPub
                    startActivity(externalManifest, book!!, externalPub, book?.cover)
                }
            }
        }
    }

    protected fun prepareAndStartActivity(pub: PubBox?, book: Book, file: File, publicationPath: String, publication: Publication, coverByteArray: ByteArray? = null) {
        prepareToServe(pub, book.fileName!!, file.absolutePath, add = false, lcp = false)
        startActivity(publicationPath, book, publication, coverByteArray)
    }

    private fun startActivity(publicationPath: String, book: Book, publication: Publication, coverByteArray: ByteArray? = null) {
        val intent = Intent(this, when (publication.type) {
            Publication.TYPE.AUDIO -> AudiobookActivity::class.java
            Publication.TYPE.CBZ -> ComicActivity::class.java
            Publication.TYPE.DiViNa -> DiViNaActivity::class.java
            else -> EpubActivity::class.java
        })

        intent.apply {
            putPublication(publication)
            putExtra("publicationPath", publicationPath)
            putExtra("publicationFileName", book.fileName)
            putExtra("bookId", book.id)
            putExtra("cover", coverByteArray)
        }

        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        data ?: return


        // The document selected by the user won't be returned in the intent.
        // Instead, a URI to that document will be contained in the return intent
        // provided to this method as a parameter.
        // Pull that URI using resultData.getData().
        if (requestCode == 1 && resultCode == RESULT_OK) {

            val progress = indeterminateProgressDialog(getString(R.string.progress_wait_while_downloading_book))

            task {

                progress.show()

            } then {
                val uri = data.data
                val format = uri?.let { runBlocking { Format.ofUri(it, contentResolver) } }
                if (format != null) {
                    if (format == Format.LCP_LICENSE) {
                        processLcpActivityResult(uri, progress, isNetworkAvailable)
                    } else {
                        processEpubResult(uri, format, progress)
                    }
                } else {
                    catalogView.longSnackbar("Unsupported file")
                    progress.dismiss()
                }
            }

        } else if (resultCode == RESULT_OK) {
            val progress = indeterminateProgressDialog(getString(R.string.progress_wait_while_downloading_book))
            progress.show()

            task {
                val filePath = data.getStringExtra("resultPath")
                parseIntent(filePath)
            } then {
                progress.dismiss()
            }

        }
    }


    private fun processEpubResult(uri: Uri?, format: Format, progress: ProgressDialog) {
        val fileName = UUID.randomUUID().toString()
        val publicationPath = R2DIRECTORY + fileName

        val input = contentResolver.openInputStream(uri as Uri)

        launch {
            when (format) {
                Format.DIVINA, Format.READIUM_AUDIOBOOK -> {
                    val output = File(publicationPath)
                    if (!output.exists()) {
                        if (!output.mkdir()) {
                            throw RuntimeException("Cannot create directory")
                        }
                    }
                    ZipUtil.unpack(input, output)
                }
                else -> input?.toFile(publicationPath)
            }

            val file = File(publicationPath)

            try {
                val publication = Publication.parse(publicationPath, format)
                if (publication != null) {
                    prepareToServe(publication, fileName, file.absolutePath, add = true, lcp = publication.container.drm?.brand == DRM.Brand.lcp)
                    progress.dismiss()
                } else {
                    catalogView.longSnackbar("Unsupported file")
                    progress.dismiss()
                    file.delete()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                progress.dismiss()
            }
        }
    }

    class VerticalSpaceItemDecoration(private val verticalSpaceHeight: Int) : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {

        override fun getItemOffsets(outRect: Rect, view: View, parent: androidx.recyclerview.widget.RecyclerView,
                                    state: androidx.recyclerview.widget.RecyclerView.State) {
            outRect.bottom = verticalSpaceHeight
        }
    }


    override fun parseIntentLcpl(uriString: String, networkAvailable: Boolean) {
        listener?.parseIntentLcpl(uriString, networkAvailable)
    }

    override fun prepareAndStartActivityWithLCP(drm: DRM, pub: PubBox, book: Book, file: File, publicationPath: String, publication: Publication, networkAvailable: Boolean) {
        listener?.prepareAndStartActivityWithLCP(drm, pub, book, file, publicationPath, publication, networkAvailable)
    }

    override fun processLcpActivityResult(uri: Uri, progress: ProgressDialog, networkAvailable: Boolean) {
        listener?.processLcpActivityResult(uri, progress, networkAvailable)
    }

    companion object {

        var isServerStarted = false
            private set

    }

}
