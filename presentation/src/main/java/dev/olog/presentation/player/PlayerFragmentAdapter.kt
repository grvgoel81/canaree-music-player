package dev.olog.presentation.player

import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import dev.olog.core.MediaId
import dev.olog.core.prefs.MusicPreferencesGateway
import dev.olog.media.model.PlayerMetadata
import dev.olog.presentation.BR
import dev.olog.presentation.R
import dev.olog.presentation.base.adapter.*
import dev.olog.presentation.base.drag.IDragListener
import dev.olog.presentation.base.drag.TouchableAdapter
import dev.olog.presentation.interfaces.HasSlidingPanel
import dev.olog.presentation.model.DisplayableItem
import dev.olog.presentation.navigator.Navigator
import dev.olog.presentation.player.volume.PlayerVolumeFragment
import dev.olog.presentation.utils.isCollapsed
import dev.olog.presentation.utils.isExpanded
import dev.olog.presentation.widgets.SwipeableView
import dev.olog.presentation.widgets.audiowave.AudioWaveViewWrapper
import dev.olog.shared.extensions.*
import dev.olog.media.model.PlayerPlaybackState
import dev.olog.media.model.PlayerState
import dev.olog.media.MediaProvider
import dev.olog.shared.theme.hasPlayerAppearance
import dev.olog.shared.utils.TextUtils
import dev.olog.shared.widgets.AnimatedImageView
import dev.olog.shared.widgets.playpause.AnimatedPlayPauseImageView
import kotlinx.android.synthetic.main.player_controls_default.view.*
import kotlinx.android.synthetic.main.player_layout_default.view.*
import kotlinx.android.synthetic.main.player_toolbar_default.view.*
import java.lang.IllegalArgumentException

internal class PlayerFragmentAdapter(
    lifecycle: Lifecycle,
    private val mediaProvider: MediaProvider,
    private val navigator: Navigator,
    private val viewModel: PlayerFragmentViewModel,
    private val presenter: PlayerFragmentPresenter,
    private val musicPrefs: MusicPreferencesGateway,
    private val dragListener: IDragListener,
    private val playerApperanceAdaptiveBehavior: IPlayerApperanceAdaptiveBehavior

) : ObservableAdapter<DisplayableItem>(lifecycle,
    DiffCallbackDisplayableItem
), TouchableAdapter {

    private val playerViewTypes = listOf(
        R.layout.player_layout_default,
        R.layout.player_layout_spotify,
        R.layout.player_layout_flat,
        R.layout.player_layout_big_image,
        R.layout.player_layout_fullscreen,
        R.layout.player_layout_clean,
        R.layout.player_layout_mini
    )

    override fun initViewHolderListeners(viewHolder: DataBoundViewHolder, viewType: Int) {
        when (viewType) {
            R.layout.item_mini_queue -> {
                viewHolder.setOnClickListener(this) { item, _, _ ->
                    mediaProvider.skipToQueueItem(item.trackNumber.toInt())
                }
                viewHolder.setOnLongClickListener(this) { item, _, _ ->
                    navigator.toDialog(item, viewHolder.itemView)
                }
                viewHolder.setOnClickListener(R.id.more, this) { item, _, view ->
                    navigator.toDialog(item, view)
                }
                viewHolder.elevateAlbumOnTouch()

                viewHolder.setOnDragListener(R.id.dragHandle, dragListener)
            }
            R.layout.player_layout_default,
            R.layout.player_layout_spotify,
            R.layout.player_layout_fullscreen,
            R.layout.player_layout_flat,
            R.layout.player_layout_big_image,
            R.layout.player_layout_clean,
            R.layout.player_layout_mini -> {
                setupListeners(viewHolder)

                viewHolder.setOnClickListener(R.id.more, this) { _, _, view ->
                    val mediaId = MediaId.songId(viewModel.getCurrentTrackId())
                    navigator.toDialog(mediaId, view)
                }
                viewHolder.itemView.volume.musicPrefs = musicPrefs
            }
        }

    }

    override fun onViewAttachedToWindow(holder: DataBoundViewHolder) {
        super.onViewAttachedToWindow(holder)

        val viewType = holder.itemViewType

        if (viewType in playerViewTypes) {

            val view = holder.itemView
            view.imageSwitcher.observeProcessorColors()
                .asLiveData()
                .subscribe(holder, viewModel::updateProcessorColors)
            view.imageSwitcher.observePaletteColors()
                .asLiveData()
                .subscribe(holder, viewModel::updatePaletteColors)

            bindPlayerControls(holder, view)

            playerApperanceAdaptiveBehavior(holder, viewModel)
        }
    }

    private fun setupListeners(holder: DataBoundViewHolder) {
        val view = holder.itemView
        view.repeat.setOnClickListener { mediaProvider.toggleRepeatMode() }
        view.shuffle.setOnClickListener { mediaProvider.toggleShuffleMode() }
        view.favorite.setOnClickListener {
            view.favorite.toggleFavorite()
            mediaProvider.togglePlayerFavorite()
        }
        view.lyrics.setOnClickListener { navigator.toOfflineLyrics() }
        view.next.setOnClickListener { mediaProvider.skipToNext() }
        view.playPause.setOnClickListener { mediaProvider.playPause() }
        view.previous.setOnClickListener { mediaProvider.skipToPrevious() }

        view.replay.setOnClickListener {
            it.rotate(-30f)
            mediaProvider.replayTenSeconds()
        }

        view.replay30.setOnClickListener {
            it.rotate(-50f)
            mediaProvider.replayThirtySeconds()
        }

        view.forward.setOnClickListener {
            it.rotate(30f)
            mediaProvider.forwardTenSeconds()
        }

        view.forward30.setOnClickListener {
            it.rotate(50f)
            mediaProvider.forwardThirtySeconds()
        }

        view.playbackSpeed.setOnClickListener { openPlaybackSpeedPopup(it) }

        view.seekBar.setListener(
            onProgressChanged = {
                view.bookmark.text = TextUtils.formatMillis(it)
            }, onStartTouch = {

            }, onStopTouch = {
                mediaProvider.seekTo(it.toLong())
            }
        )
    }

    private fun bindPlayerControls(holder: DataBoundViewHolder, view: View) {

        val waveWrapper: AudioWaveViewWrapper? = view.findViewById(R.id.waveWrapper)

        view.findViewById<AnimatedImageView>(R.id.next)?.setDefaultColor()
        view.findViewById<AnimatedImageView>(R.id.previous)?.setDefaultColor()
        view.findViewById<AnimatedPlayPauseImageView>(R.id.playPause)?.setDefaultColor()

        mediaProvider.observeMetadata()
            .subscribe(holder) {
                viewModel.updateCurrentTrackId(it.id)
                waveWrapper?.onTrackChanged(it.path)
                waveWrapper?.updateMax(it.duration)

                updateMetadata(view, it)
                updateImage(view, it)
            }

        view.volume.setOnClickListener {
            (view.context as FragmentActivity).fragmentTransaction {
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                add(android.R.id.content, PlayerVolumeFragment(), PlayerVolumeFragment.TAG)
                addToBackStack(PlayerVolumeFragment.TAG)
            }
        }

        mediaProvider.observePlaybackState()
            .subscribe(holder) { onPlaybackStateChanged(view, it) }

        mediaProvider.observePlaybackState()
            .subscribe(holder) { view.seekBar.onStateChanged(it) }

        view.seekBar.observeProgress()
            .asLiveData()
            .subscribe(holder) { waveWrapper?.updateProgress(it.toInt()) }

        mediaProvider.observeRepeat()
            .subscribe(holder, view.repeat::cycle)

        mediaProvider.observeShuffle()
            .subscribe(holder, view.shuffle::cycle)

        view.swipeableView?.setOnSwipeListener(object : SwipeableView.SwipeListener {
            override fun onSwipedLeft() {
                mediaProvider.skipToNext()
            }

            override fun onSwipedRight() {
                mediaProvider.skipToPrevious()
            }

            override fun onClick() {
                mediaProvider.playPause()
            }

            override fun onLeftEdgeClick() {
                mediaProvider.skipToPrevious()
            }

            override fun onRightEdgeClick() {
                mediaProvider.skipToNext()
            }
        })

        viewModel.onFavoriteStateChanged
            .asLiveData()
            .subscribe(holder, view.favorite::onNextState)

        viewModel.skipToNextVisibility
            .asLiveData()
            .subscribe(holder, view.next::updateVisibility)

        viewModel.skipToPreviousVisibility
            .asLiveData()
            .subscribe(holder, view.previous::updateVisibility)

        val playerAppearance = view.context.hasPlayerAppearance()

        presenter.observePlayerControlsVisibility()
            .filter { !playerAppearance.isFullscreen() && !playerAppearance.isMini() }
            .asLiveData()
            .subscribe(holder) { visible ->
                view.previous.toggleVisibility(visible, true)
                view.playPause.toggleVisibility(visible, true)
                view.next.toggleVisibility(visible, true)
            }


        if (playerAppearance.isFullscreen() || playerAppearance.isMini()) {

            mediaProvider.observePlaybackState()
                .filter { it.isSkipTo }
                .map { state -> state.state == PlayerState.SKIP_TO_NEXT }
                .subscribe(holder) {
                    animateSkipTo(view, it) }

            mediaProvider.observePlaybackState()
                .filter { it.isPlayOrPause }
                .distinctUntilChanged()
                .map { it.state }
                .subscribe(holder) { state ->
                    when (state){
                        PlayerState.PLAYING -> playAnimation(view)
                        PlayerState.PAUSED -> pauseAnimation(view)
                        else -> throw IllegalArgumentException("invalid state ${state}")
                    }
                }
        }
    }

    private fun updateMetadata(view: View, metadata: PlayerMetadata) {
        view.title.text = metadata.title
        view.artist.text = metadata.artist

        val duration = metadata.duration

        val readableDuration = metadata.readableDuration
        view.duration.text = readableDuration
        view.seekBar.max = duration.toInt()

        val isPodcast = metadata.isPodcast
        val playerControlsRoot: ConstraintLayout = view.findViewById(R.id.playerControls)
            ?: view.findViewById(R.id.playerRoot) as ConstraintLayout
        playerControlsRoot.findViewById<View>(R.id.replay).toggleVisibility(isPodcast, true)
        playerControlsRoot.findViewById<View>(R.id.forward).toggleVisibility(isPodcast, true)
        playerControlsRoot.findViewById<View>(R.id.replay30).toggleVisibility(isPodcast, true)
        playerControlsRoot.findViewById<View>(R.id.forward30).toggleVisibility(isPodcast, true)
    }

    private fun updateImage(view: View, metadata: PlayerMetadata) {
        view.imageSwitcher.loadImage(metadata)
    }

    private fun openPlaybackSpeedPopup(view: View) {
        val popup = PopupMenu(view.context, view)
        popup.inflate(R.menu.dialog_playback_speed)
        popup.menu.getItem(viewModel.getPlaybackSpeed()).isChecked = true
        popup.setOnMenuItemClickListener {
            viewModel.setPlaybackSpeed(it.itemId)
            true
        }
        popup.show()
    }

    private fun onPlaybackStateChanged(view: View, playbackState: PlayerPlaybackState) {
        val isPlaying = playbackState.isPlaying

        if (isPlaying || playbackState.isPaused) {
            view.nowPlaying.isActivated = isPlaying
            val playerAppearance = view.context.hasPlayerAppearance()
            if (playerAppearance.isClean()) {
//                view.bigCover.isActivated = isPlaying TODO
            } else {
                // TODO
                view.imageSwitcher.setChildrenActivated(isPlaying)
            }
        }
    }

    private fun animateSkipTo(view: View, toNext: Boolean) {
        val hasSlidingPanel = (view.context) as HasSlidingPanel
        if (hasSlidingPanel.getSlidingPanel().isCollapsed()) return

        if (toNext) {
            view.next.playAnimation()
        } else {
            view.previous.playAnimation()
        }
    }

    private fun playAnimation(view: View) {
        val hasSlidingPanel = (view.context) as HasSlidingPanel
        val isPanelExpanded = hasSlidingPanel.getSlidingPanel().isExpanded()
        view.playPause.animationPlay(isPanelExpanded)
    }

    private fun pauseAnimation(view: View) {
        val hasSlidingPanel = (view.context) as HasSlidingPanel
        val isPanelExpanded = hasSlidingPanel.getSlidingPanel().isExpanded()
        view.playPause.animationPause(isPanelExpanded)
    }

    override fun bind(binding: ViewDataBinding, item: DisplayableItem, position: Int) {
        binding.setVariable(BR.item, item)
    }

    override fun canInteractWithViewHolder(viewType: Int): Boolean {
        return viewType == R.layout.item_mini_queue
    }

    override fun onMoved(from: Int, to: Int) {
        val realFrom = from - 1
        val realTo = to - 1
        mediaProvider.swapRelative(realFrom, realTo)
        dataSet.swap(from, to)
        notifyItemMoved(from ,to)
    }

    override fun onSwipedRight(viewHolder: RecyclerView.ViewHolder) {
        val realPosition = viewHolder.adapterPosition - 1
        mediaProvider.removeRelative(realPosition)
    }

    override fun afterSwipeRight(viewHolder: RecyclerView.ViewHolder) {
        dataSet.removeAt(viewHolder.adapterPosition)
        notifyItemRemoved(viewHolder.adapterPosition)
    }

}