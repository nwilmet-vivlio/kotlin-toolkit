/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.domain

import android.net.Uri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.protection.ContentProtectionSchemeRetriever
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.ResourceError
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationFactory
import org.readium.r2.testapp.data.BookRepository
import org.readium.r2.testapp.data.model.Book
import org.readium.r2.testapp.utils.extensions.formatPercentage
import org.readium.r2.testapp.utils.tryOrLog
import timber.log.Timber

/**
 * The [Bookshelf] supports two different processes:
 * - directly _adding_ the url to a remote asset or an asset from shared storage to the database
 * - _importing_ an asset, that is downloading or copying the publication the asset points to to the app storage
 *   before adding it to the database
 */
class Bookshelf(
    private val bookRepository: BookRepository,
    private val coverStorage: CoverStorage,
    private val publicationFactory: PublicationFactory,
    private val assetRetriever: AssetRetriever,
    private val protectionRetriever: ContentProtectionSchemeRetriever,
    createPublicationRetriever: (PublicationRetriever.Listener) -> PublicationRetriever
) {
    val channel: Channel<Event> =
        Channel(Channel.UNLIMITED)

    private val publicationRetriever: PublicationRetriever

    init {
        publicationRetriever = createPublicationRetriever(PublicationRetrieverListener())
    }

    sealed class Event {
        data object ImportPublicationSuccess :
            Event()

        class ImportPublicationError(
            val error: ImportError
        ) : Event()
    }

    private val coroutineScope: CoroutineScope =
        MainScope()

    private inner class PublicationRetrieverListener : PublicationRetriever.Listener {
        override fun onSuccess(publication: File, coverUrl: AbsoluteUrl?) {
            coroutineScope.launch {
                val url = publication.toUrl()
                addBookFeedback(url, coverUrl)
            }
        }

        override fun onProgressed(progress: Double) {
            Timber.e("Downloaded ${progress.formatPercentage()}")
        }

        override fun onError(error: ImportError) {
            coroutineScope.launch {
                channel.send(Event.ImportPublicationError(error))
            }
        }
    }

    fun importPublicationFromStorage(
        uri: Uri
    ) {
        publicationRetriever.retrieveFromStorage(uri)
    }

    fun importPublicationFromOpds(
        publication: Publication
    ) {
        publicationRetriever.retrieveFromOpds(publication)
    }

    fun addPublicationFromWeb(
        url: Url
    ) {
        coroutineScope.launch {
            addBookFeedback(url)
        }
    }

    fun addPublicationFromStorage(
        url: Url
    ) {
        coroutineScope.launch {
            addBookFeedback(url)
        }
    }

    private suspend fun addBookFeedback(
        url: Url,
        coverUrl: AbsoluteUrl? = null
    ) {
        addBook(url, coverUrl)
            .onSuccess { channel.send(Event.ImportPublicationSuccess) }
            .onFailure { channel.send(Event.ImportPublicationError(it)) }
    }

    private suspend fun addBook(
        url: Url,
        coverUrl: AbsoluteUrl? = null
    ): Try<Unit, ImportError> {
        val asset =
            assetRetriever.retrieve(url)
                ?: return Try.failure(
                    ImportError.PublicationError(PublicationError.UnsupportedAsset())
                )

        val drmScheme =
            protectionRetriever.retrieve(asset)

        publicationFactory.open(
            asset,
            contentProtectionScheme = drmScheme,
            allowUserInteraction = false
        ).onSuccess { publication ->
            val coverFile =
                coverStorage.storeCover(publication, coverUrl)
                    .getOrElse {
                        return Try.failure(ImportError.ResourceError(ResourceError.Filesystem(it)))
                    }

            val id = bookRepository.insertBook(
                url.toString(),
                asset.mediaType,
                asset.assetType,
                drmScheme,
                publication,
                coverFile
            )
            if (id == -1L) {
                coverFile.delete()
                return Try.failure(ImportError.DatabaseError())
            }
        }
            .onFailure {
                Timber.e("Cannot open publication: $it.")
                return Try.failure(
                    ImportError.PublicationError(PublicationError(it))
                )
            }

        return Try.success(Unit)
    }

    suspend fun deleteBook(book: Book) {
        val id = book.id!!
        bookRepository.deleteBook(id)
        tryOrLog { book.url.toFile()?.delete() }
        tryOrLog { File(book.cover).delete() }
    }
}
