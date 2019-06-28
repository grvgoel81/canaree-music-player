package dev.olog.service.music.queue

import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.olog.injection.dagger.ServiceLifecycle
import dev.olog.service.music.PlayerState
import dev.olog.service.music.model.MediaEntity
import dev.olog.service.music.model.MediaSessionQueueModel
import dev.olog.shared.extensions.unsubscribe
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MediaSessionQueue @Inject constructor(
    @ServiceLifecycle lifecycle: Lifecycle,
    mediaSession: MediaSessionCompat,
    private val playerState: PlayerState

) : DefaultLifecycleObserver {

    private val publisher: PublishSubject<MediaSessionQueueModel<MediaEntity>> =
        PublishSubject.create()
    private val immediatePublisher: PublishSubject<MediaSessionQueueModel<MediaEntity>> =
        PublishSubject.create()
    private var miniQueueDisposable: Disposable? = null
    private var immediateMiniQueueDisposable: Disposable? = null

    init {
        lifecycle.addObserver(this)

        miniQueueDisposable = publisher
            .toSerialized()
            .observeOn(Schedulers.computation())
            .distinctUntilChanged()
            .debounce(1, TimeUnit.SECONDS)
            .map { it.toQueueItem() }
            .subscribe({ (id, queue) ->
                mediaSession.setQueue(queue)
                playerState.updateActiveQueueId(id)
            }, Throwable::printStackTrace)

        immediateMiniQueueDisposable = immediatePublisher
            .toSerialized()
            .observeOn(Schedulers.computation())
            .distinctUntilChanged()
            .map { it.toQueueItem() }
            .subscribe({ (id, queue) ->
                mediaSession.setQueue(queue)
                playerState.updateActiveQueueId(id)
            }, Throwable::printStackTrace)
    }

    fun onNext(list: MediaSessionQueueModel<MediaEntity>) {
        publisher.onNext(list)
    }

    fun onNextImmediate(list: MediaSessionQueueModel<MediaEntity>) {
        immediatePublisher.onNext(list)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        miniQueueDisposable.unsubscribe()
        immediateMiniQueueDisposable.unsubscribe()
    }

    private fun MediaEntity.toQueueItem(): MediaSessionCompat.QueueItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId.toString())
            .setTitle(this.title)
            .setSubtitle(this.artist)
//                .setMediaUri(Uri.parse(this.image)) TODO ??
            .build()

        return MediaSessionCompat.QueueItem(description, this.idInPlaylist.toLong())
    }

    private fun MediaSessionQueueModel<MediaEntity>.toQueueItem(): MediaSessionQueueModel<MediaSessionCompat.QueueItem> {
        val queue = this.queue.map { it.toQueueItem() }
        return MediaSessionQueueModel(this.activeId, queue)
    }

}

