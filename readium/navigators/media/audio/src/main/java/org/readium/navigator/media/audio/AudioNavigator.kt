/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.audio

import androidx.media3.common.Player
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import org.readium.navigator.media.common.Media3Adapter
import org.readium.navigator.media.common.MediaNavigator
import org.readium.navigator.media.common.TimeBasedMediaNavigator
import org.readium.r2.navigator.extensions.normalizeLocator
import org.readium.r2.navigator.extensions.sum
import org.readium.r2.navigator.extensions.time
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.extensions.mapStateIn
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import timber.log.Timber

@ExperimentalReadiumApi
@OptIn(ExperimentalTime::class, DelicateReadiumApi::class)
public class AudioNavigator<S : Configurable.Settings, P : Configurable.Preferences<P>> internal constructor(
    private val publication: Publication,
    private val audioEngine: AudioEngine<S, P>,
    override val readingOrder: ReadingOrder
) :
    MediaNavigator<AudioNavigator.Location, AudioNavigator.Playback, AudioNavigator.ReadingOrder>,
    TimeBasedMediaNavigator<AudioNavigator.Location, AudioNavigator.Playback, AudioNavigator.ReadingOrder>,
    Media3Adapter,
    Configurable<S, P> by audioEngine {

    public data class Location(
        override val href: Url,
        override val offset: Duration
    ) : TimeBasedMediaNavigator.Location

    public data class ReadingOrder(
        override val duration: Duration?,
        override val items: List<Item>
    ) : TimeBasedMediaNavigator.ReadingOrder {

        public data class Item(
            val href: Url,
            override val duration: Duration?
        ) : TimeBasedMediaNavigator.ReadingOrder.Item
    }

    public data class Playback(
        override val state: MediaNavigator.State,
        override val playWhenReady: Boolean,
        override val index: Int,
        override val offset: Duration,
        override val buffered: Duration?
    ) : TimeBasedMediaNavigator.Playback

    public sealed class State {

        public object Ready : MediaNavigator.State.Ready

        public object Ended : MediaNavigator.State.Ended

        public object Buffering : MediaNavigator.State.Buffering

        public data class Error<E : AudioEngine.Error> (val error: E) : MediaNavigator.State.Error
    }

    private val coroutineScope: CoroutineScope =
        MainScope()

    override val currentLocator: StateFlow<Locator> =
        audioEngine.playback.mapStateIn(coroutineScope) { playback ->
            val currentItem = readingOrder.items[playback.index]
            val link = requireNotNull(publication.linkWithHref(currentItem.href))
            val item = readingOrder.items[playback.index]
            val itemStartPosition = readingOrder.items
                .slice(0 until playback.index)
                .mapNotNull { it.duration }
                .takeIf { it.size == readingOrder.items.size }
                ?.sum()
            val totalProgression =
                if (itemStartPosition == null) {
                    null
                } else {
                    readingOrder.duration?.let { (itemStartPosition + playback.offset) / it }
                }

            val locator = requireNotNull(publication.locatorFromLink(link))
            locator.copyWithLocations(
                fragments = listOf("t=${playback.offset.inWholeSeconds}"),
                progression = item.duration?.let { playback.offset / it },
                totalProgression = totalProgression
            )
        }

    override val playback: StateFlow<Playback> =
        audioEngine.playback.mapStateIn(coroutineScope) { playback ->
            Playback(
                playback.state.toState(),
                playback.playWhenReady,
                playback.index,
                playback.offset,
                playback.buffered
            )
        }

    override val location: StateFlow<Location> =
        audioEngine.playback.mapStateIn(coroutineScope) {
            val currentItem = readingOrder.items[it.index]
            Location(currentItem.href, it.offset)
        }

    override fun play() {
        audioEngine.play()
    }

    override fun pause() {
        audioEngine.pause()
    }

    override fun skipTo(index: Int, offset: Duration) {
        audioEngine.skipTo(index, offset)
    }

    public override fun skipForward() {
        audioEngine.skipForward()
    }

    public override fun skipBackward() {
        audioEngine.skipBackward()
    }

    public override fun skip(duration: Duration) {
        audioEngine.skip(duration)
    }

    override fun close() {
        audioEngine.close()
    }

    override fun asMedia3Player(): Player =
        audioEngine.asPlayer()

    override fun go(locator: Locator, animated: Boolean, completion: () -> Unit): Boolean {
        @Suppress("NAME_SHADOWING")
        val locator = publication.normalizeLocator(locator)
        val itemIndex = readingOrder.items.indexOfFirst { it.href == locator.href }
            .takeUnless { it == -1 }
            ?: return false
        val position = locator.locations.time ?: Duration.ZERO
        Timber.v("Go to locator $locator")
        audioEngine.skipTo(itemIndex, position)
        return true
    }

    override fun go(link: Link, animated: Boolean, completion: () -> Unit): Boolean {
        val locator = publication.locatorFromLink(link) ?: return false
        return go(locator, animated, completion)
    }

    private fun AudioEngine.State.toState(): MediaNavigator.State =
        when (this) {
            is AudioEngine.State.Ready -> State.Ready
            is AudioEngine.State.Ended -> State.Ended
            is AudioEngine.State.Buffering -> State.Buffering
            is AudioEngine.State.Error -> State.Error(error)
        }
}
