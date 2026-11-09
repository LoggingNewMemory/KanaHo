/*
 * Inaho Music Player - Media Session Manager
 * Copyright (C) 2026 Kanagawa Yamada
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package kana.wesley.yamada.kanaho.inahoservice

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kana.wesley.yamada.kanaho.EqPreset
import kana.wesley.yamada.kanaho.MainActivity
import kana.wesley.yamada.kanaho.Song
import kana.wesley.yamada.kanaho.extractAndDownsample
import kana.wesley.yamada.kanaho.inahocar.AutoLibraryManager
import kana.wesley.yamada.kanaho.isArtResolved
import kana.wesley.yamada.kanaho.saveBitmapToDisk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class MediaSessionManager(
    private val service: PlayerService
) {
    val player: InahoPlayer
    val mediaLibrarySession: MediaLibrarySession

    init {
        player = InahoPlayer(service)

        val sessionActivityIntent = Intent(service, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            service, 0, sessionActivityIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId(AutoLibraryManager.ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val children = service.autoLibraryManager.getChildren(parentId)
                return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val item = service.autoLibraryManager.getItem(mediaId)
                return if (item != null) {
                    Futures.immediateFuture(LibraryResult.ofItem(item, null))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<List<MediaItem>> {
                for (item in mediaItems) {
                    val mediaId = item.mediaId
                    val searchQuery = item.requestMetadata.searchQuery
                    if (!searchQuery.isNullOrBlank()) {
                        service.playFromSearch(searchQuery)
                        return Futures.immediateFuture(emptyList())
                    }
                    if (mediaId == AutoLibraryManager.CATEGORY_EQ) {
                        if (PlayerService.playerState.value.currentSong == null) {
                            service.playRandomSong()
                        } else {
                            service.updateSessionAndNotification()
                        }
                        return Futures.immediateFuture(emptyList())
                    }
                    if (mediaId == "action_toggle_replaygain") {
                        val isEnabled = service.eqEngine.replayGainEnabled.value
                        service.eqEngine.setReplayGainEnabled(!isEnabled)
                        if (PlayerService.playerState.value.currentSong == null) {
                            service.playRandomSong()
                        } else {
                            service.updateSessionAndNotification()
                        }
                        service.notifyChildrenChanged(AutoLibraryManager.CATEGORY_EQ)
                        return Futures.immediateFuture(emptyList())
                    }
                    if (mediaId.startsWith(AutoLibraryManager.PREFIX_EQ)) {
                        val presetName = mediaId.removePrefix(AutoLibraryManager.PREFIX_EQ)
                        val preset = EqPreset.values().find { it.name == presetName }
                        if (preset != null) {
                            service.eqEngine.setPreset(preset)
                            if (PlayerService.playerState.value.currentSong == null) {
                                service.playRandomSong()
                            } else {
                                service.updateSessionAndNotification()
                            }
                            service.notifyChildrenChanged(AutoLibraryManager.CATEGORY_EQ)
                        }
                        return Futures.immediateFuture(emptyList())
                    }
                    val id = mediaId.toLongOrNull()
                    if (id != null) {
                        service.playFromMediaId(id)
                        return Futures.immediateFuture(emptyList())
                    }
                }
                return Futures.immediateFuture(mediaItems)
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(service, player, callback)
            .setId("InahoMediaSession")
            .setSessionActivity(pendingIntent)
            .build()
    }

    fun updateState(state: PlayerState, currentPosition: Long) {
        player.requestStateUpdate()
    }

    fun notifyChildrenChanged(parentId: String) {
        mediaLibrarySession.notifyChildrenChanged(parentId, Int.MAX_VALUE, null)
    }

    fun release() {
        mediaLibrarySession.release()
        player.release()
    }
}

@OptIn(UnstableApi::class)
class InahoPlayer(
    private val service: PlayerService,
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {

    fun requestStateUpdate() {
        invalidateState()
    }

    private val availableCommands = Player.Commands.Builder()
        .addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_PREPARE,
            COMMAND_STOP,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SET_SHUFFLE_MODE,
            COMMAND_SET_REPEAT_MODE,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
            COMMAND_SET_MEDIA_ITEM
        )
        .build()

    private fun createMediaItem(song: Song): MediaItem {
        val artFile = File(service.cacheDir, "art/${song.id}.png")
        val artUri = if (artFile.exists()) Uri.fromFile(artFile) else null
        val artBytes = if (artFile.exists()) {
            try { artFile.readBytes() } catch (_: Exception) { null }
        } else {
            if (!isArtResolved(service, song.id)) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val bitmap = extractAndDownsample(service, song.trackUri, 512)
                        saveBitmapToDisk(service, song.id, bitmap)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                invalidateState()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            null
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setArtworkUri(artUri)
            .setIsPlayable(true)
            .setIsBrowsable(false)

        if (artBytes != null) {
            metadataBuilder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(song.trackUri)
                    .build()
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    override fun getState(): State {
        val state = PlayerService.playerState.value
        val queue = state.activeQueue
        val currentSong = state.currentSong

        val playlistData = if (queue.isNotEmpty()) {
            queue.map { song ->
                MediaItemData.Builder(song.id)
                    .setMediaItem(createMediaItem(song))
                    .setDurationUs(song.durationMs * 1000L)
                    .build()
            }
        } else if (currentSong != null) {
            listOf(
                MediaItemData.Builder(currentSong.id)
                    .setMediaItem(createMediaItem(currentSong))
                    .setDurationUs(currentSong.durationMs * 1000L)
                    .build()
            )
        } else {
            emptyList()
        }

        val playbackState = if (currentSong != null) STATE_READY else STATE_IDLE
        val currentIndex = state.currentIndex.coerceAtLeast(0)

        val builder = State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlayWhenReady(state.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setRepeatMode(
                when (state.repeatMode) {
                    RepeatMode.OFF -> REPEAT_MODE_OFF
                    RepeatMode.ALL -> REPEAT_MODE_ALL
                    RepeatMode.ONE -> REPEAT_MODE_ONE
                }
            )
            .setShuffleModeEnabled(state.isShuffled)
            .setContentPositionMs { service.getCurrentPosition() }

        if (playlistData.isNotEmpty()) {
            builder.setPlaylist(playlistData)
            builder.setCurrentMediaItemIndex(currentIndex.coerceIn(0, playlistData.size - 1))
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (PlayerService.playerState.value.currentSong == null) {
                service.playRandomSong()
            } else if (!PlayerService.playerState.value.isPlaying) {
                service.togglePlayPause()
            }
        } else {
            if (PlayerService.playerState.value.isPlaying) {
                service.togglePlayPause()
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                if (positionMs != C.TIME_UNSET) {
                    service.seekTo(positionMs)
                }
            }
            COMMAND_SEEK_TO_MEDIA_ITEM -> {
                if (mediaItemIndex != C.INDEX_UNSET) {
                    service.jumpToQueueIndex(mediaItemIndex)
                }
                if (positionMs != C.TIME_UNSET) {
                    service.seekTo(positionMs)
                }
            }
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                service.skipNext()
            }
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                service.skipPrev()
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        service.toggleRepeat()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled != PlayerService.playerState.value.isShuffled) {
            service.toggleShuffle()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        service.stopPlayback()
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
