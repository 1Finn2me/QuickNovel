package com.lagradost.quicknovel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.session.MediaButtonReceiver
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.UiText
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages TTS (Text-to-Speech) notifications and media session for the reading experience.
 *
 * Responsibilities:
 * - Creating and managing media-style notifications for TTS playback
 * - Managing the MediaSession for hardware/software media button handling
 * - Responding to media button events (play, pause, skip, etc.)
 * - Handling notification permissions and channels
 *
 * Thread Safety:
 * - Uses AtomicBoolean for channel creation flag
 * - MediaSession access should be done on main thread
 *
 * @see MediaSessionCompat
 * @see TTSHelper
 */
object TTSNotifications {

    //region Constants

    private const val TTS_CHANNEL_ID = "QuickNovelTTS"
    private const val MEDIA_SESSION_TAG = "QuickNovelTTS"

    /** Unique notification ID for TTS notifications */
    const val TTS_NOTIFICATION_ID = 133742

    /** Indices of actions to show in compact notification view */
    private const val COMPACT_ACTION_REWIND = 0
    private const val COMPACT_ACTION_STOP = 1
    private const val COMPACT_ACTION_PLAY_PAUSE = 2
    private const val COMPACT_ACTION_FORWARD = 3


    //endregion

    //region State

    /** Thread-safe flag for notification channel creation */
    private val hasCreatedNotificationChannel = AtomicBoolean(false)

    /** Current media session, should only be accessed from main thread */
    @Volatile
    private var _mediaSession: MediaSessionCompat? = null
    val mediaSession: MediaSessionCompat?
        get() = _mediaSession

    /** Weak reference to avoid memory leaks with ViewModel */
    private var viewModelRef: WeakReference<ReadActivityViewModel>? = null

    //endregion

    //region Notification Channel

    /**
     * Creates the notification channel for TTS notifications.
     * Required for Android O (API 26) and above.
     *
     * Channel configuration:
     * - Default importance for persistent display
     * - Silent (no sound or vibration)
     * - Visible on lock screen
     *
     * @param context The context used for creating the channel
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            TTS_CHANNEL_ID,
            context.getString(R.string.text_to_speech),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.text_to_speech_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }

        context.notificationManager.createNotificationChannel(channel)
    }

    /**
     * Ensures the notification channel exists, creating it if necessary.
     * Thread-safe implementation using AtomicBoolean for one-time initialization.
     *
     * @param context The context used for channel creation
     */
    private fun ensureNotificationChannel(context: Context) {
        if (hasCreatedNotificationChannel.compareAndSet(false, true)) {
            createNotificationChannel(context)
        }
    }

    //endregion

    //region Media Session

    /**
     * Initializes and configures the MediaSession for TTS playback.
     *
     * This sets up:
     * - Media button handling callback
     * - Book metadata (title, author, cover art)
     * - Supported playback actions
     *
     * @param viewModel The ViewModel that handles TTS actions
     * @param book The current book being read
     * @param context The context for creating the MediaSession
     */
    @MainThread
    fun initializeMediaSession(
        viewModel: ReadActivityViewModel,
        book: AbstractBook,
        context: Context
    ) {
        // Release any existing session first to prevent leaks
        releaseMediaSession()

        viewModelRef = WeakReference(viewModel)

        val mediaButtonIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            context,
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        )

        _mediaSession = MediaSessionCompat(
            context,
            MEDIA_SESSION_TAG,
            ComponentName(context, MediaButtonReceiver::class.java),
            mediaButtonIntent
        ).apply {
            setCallback(createMediaSessionCallback())
            setMetadata(buildMediaMetadata(book))
            setPlaybackState(buildInitialPlaybackState())
            isActive = true
        }
    }

    /**
     * Legacy method name for backward compatibility.
     * @see initializeMediaSession
     */
    @MainThread
    fun setMediaSession(viewModel: ReadActivityViewModel, book: AbstractBook, context: Context) {
        initializeMediaSession(viewModel, book, context)
    }

    /**
     * Creates the callback for handling media session events.
     */
    private fun createMediaSessionCallback(): MediaSessionCompat.Callback {
        return object : MediaSessionCompat.Callback() {

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val keyEvent = mediaButtonEvent.getKeyEventCompat()
                    ?: return super.onMediaButtonEvent(mediaButtonEvent)

                // Only handle key down events to prevent double triggering
                if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                return handleMediaKeyEvent(keyEvent.keyCode)
                        || super.onMediaButtonEvent(mediaButtonEvent)
            }

            override fun onPlay() {
                viewModelRef?.get()?.playTTS()
            }

            override fun onPause() {
                viewModelRef?.get()?.pauseTTS()
            }

            override fun onStop() {
                viewModelRef?.get()?.stopTTS()
            }

            override fun onFastForward() {
                viewModelRef?.get()?.forwardsTTS()
            }

            override fun onRewind() {
                viewModelRef?.get()?.backwardsTTS()
            }

            override fun onSkipToNext() {
                viewModelRef?.get()?.forwardsTTS()
            }

            override fun onSkipToPrevious() {
                viewModelRef?.get()?.backwardsTTS()
            }
        }
    }

    /**
     * Handles media key events from hardware buttons or other sources.
     *
     * @param keyCode The key code of the pressed button
     * @return true if the event was handled, false otherwise
     */
    private fun handleMediaKeyEvent(keyCode: Int): Boolean {
        val viewModel = viewModelRef?.get() ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                viewModel.pausePlayTTS()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                viewModel.pauseTTS()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                viewModel.playTTS()
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                viewModel.stopTTS()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                viewModel.forwardsTTS()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                viewModel.backwardsTTS()
                true
            }
            else -> false
        }
    }

    /**
     * Builds the initial playback state with all supported actions.
     */
    private fun buildInitialPlaybackState(): PlaybackStateCompat {
        return PlaybackStateCompat.Builder()
            .setActions(SUPPORTED_PLAYBACK_ACTIONS)
            .setState(PlaybackStateCompat.STATE_NONE, 0L, 1f)
            .build()
    }

    /**
     * Builds the media metadata for the current book.
     *
     * @param book The book to extract metadata from
     * @return MediaMetadataCompat with book information
     */
    private fun buildMediaMetadata(book: AbstractBook): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .apply {
                book.author()?.let { author ->
                    putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, author)
                    putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
                    putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, author)
                }

                book.poster()?.let { poster ->
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, poster)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, poster)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, poster)
                }
            }
            .build()
    }

    /**
     * Updates the playback state in the media session.
     * Call this when TTS status changes.
     *
     * @param status The current TTS status
     * @param position The current playback position in milliseconds (optional)
     */
    @MainThread
    fun updatePlaybackState(status: TTSHelper.TTSStatus, position: Long = 0L) {
        val state = status.toPlaybackState()
        val playbackSpeed = if (status == TTSHelper.TTSStatus.IsRunning) 1f else 0f

        _mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(SUPPORTED_PLAYBACK_ACTIONS)
                .setState(state, position, playbackSpeed)
                .build()
        )
    }

    /**
     * Updates the media session metadata (e.g., when chapter changes).
     *
     * @param title The new title (book/chapter name)
     * @param author The author name (optional)
     * @param albumArt The cover art bitmap (optional)
     */
    @MainThread
    fun updateMetadata(title: String?, author: String? = null, albumArt: Bitmap? = null) {
        val session = _mediaSession ?: return

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .apply {
                    title?.let { putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
                    author?.let {
                        putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, it)
                        putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
                    }
                    albumArt?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                    }
                }
                .build()
        )
    }

    /**
     * Releases the media session and cleans up resources.
     * Should be called when TTS is stopped or the activity is destroyed.
     */
    @MainThread
    fun releaseMediaSession() {
        _mediaSession?.run {
            isActive = false
            setCallback(null)
            release()
        }
        _mediaSession = null
        viewModelRef?.clear()
        viewModelRef = null
    }

    //endregion

    //region Notification Creation

    /**
     * Represents a media action button for the notification.
     */
    private enum class MediaAction(
        @DrawableRes val iconRes: Int,
        val title: String,
        val playbackAction: Long
    ) {
        PLAY(
            R.drawable.ic_baseline_play_arrow_24,
            "Resume",
            PlaybackStateCompat.ACTION_PLAY
        ),
        PAUSE(
            R.drawable.ic_baseline_pause_24,
            "Pause",
            PlaybackStateCompat.ACTION_PAUSE
        ),
        STOP(
            R.drawable.ic_baseline_stop_24,
            "Stop",
            PlaybackStateCompat.ACTION_STOP
        ),
        REWIND(
            R.drawable.ic_baseline_fast_rewind_24,
            "Rewind",
            PlaybackStateCompat.ACTION_REWIND
        ),
        FAST_FORWARD(
            R.drawable.ic_baseline_fast_forward_24,
            "Fast Forward",
            PlaybackStateCompat.ACTION_FAST_FORWARD
        );

        /**
         * Converts this MediaAction to a NotificationCompat.Action.
         */
        fun toNotificationAction(context: Context): NotificationCompat.Action {
            return NotificationCompat.Action.Builder(
                iconRes,
                title,
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, playbackAction)
            ).build()
        }
    }

    /**
     * Creates a TTS notification with the given parameters.
     *
     * @param title The title to display (usually book name)
     * @param chapter The current chapter as UiText
     * @param icon The book cover icon (optional, but recommended)
     * @param status The current TTS status
     * @param context The context for creating the notification
     * @return The created Notification, or null if status is stopped or context is null
     */
    fun createNotification(
        title: String,
        chapter: UiText,
        icon: Bitmap?,
        status: TTSHelper.TTSStatus,
        context: Context?
    ): Notification? {
        if (context == null) return null

        // Cancel and return null if TTS is stopped
        if (status == TTSHelper.TTSStatus.IsStopped) {
            cancelNotification(context)
            return null
        }

        ensureNotificationChannel(context)

        val builder = NotificationCompat.Builder(context, TTS_CHANNEL_ID)
            .configureBasicProperties(title, chapter, icon, context)

        configureMediaStyle(builder, context)
        addMediaActions(builder, status, context)

        return builder.build()
    }

    /**
     * Configures basic notification properties.
     */
    private fun NotificationCompat.Builder.configureBasicProperties(
        title: String,
        chapter: UiText,
        icon: Bitmap?,
        context: Context
    ): NotificationCompat.Builder {
        setSmallIcon(R.drawable.ic_baseline_volume_up_24)
        setContentTitle(title)
        setContentText(chapter.asString(context))
        priority = NotificationCompat.PRIORITY_LOW
        setOnlyAlertOnce(true)
        setShowWhen(false)
        setOngoing(true)
        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        icon?.let { setLargeIcon(it) }

        return this
    }

    /**
     * Configures the media style for the notification.
     */
    private fun configureMediaStyle(
        builder: NotificationCompat.Builder,
        context: Context
    ) {
        val cancelIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            context,
            PlaybackStateCompat.ACTION_STOP
        )

        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowCancelButton(true)
            .setCancelButtonIntent(cancelIntent)
            .setShowActionsInCompactView(
                COMPACT_ACTION_STOP,
                COMPACT_ACTION_PLAY_PAUSE,
                COMPACT_ACTION_FORWARD
            )

        _mediaSession?.sessionToken?.let { token ->
            style.setMediaSession(token)
        }

        builder.setStyle(style)
    }

    /**
     * Adds appropriate action buttons based on TTS status.
     */
    private fun addMediaActions(
        builder: NotificationCompat.Builder,
        status: TTSHelper.TTSStatus,
        context: Context
    ) {
        val actions = getActionsForStatus(status)
        actions.forEach { action ->
            builder.addAction(action.toNotificationAction(context))
        }
    }

    /**
     * Returns the list of actions to show based on current status.
     */
    private fun getActionsForStatus(status: TTSHelper.TTSStatus): List<MediaAction> {
        return when (status) {
            TTSHelper.TTSStatus.IsRunning -> listOf(
                MediaAction.REWIND,       // 0
                MediaAction.STOP,         // 1
                MediaAction.PAUSE,        // 2
                MediaAction.FAST_FORWARD  // 3
            )
            TTSHelper.TTSStatus.IsPaused -> listOf(
                MediaAction.REWIND,       // 0
                MediaAction.STOP,         // 1
                MediaAction.PLAY,         // 2
                MediaAction.FAST_FORWARD  // 3
            )
            TTSHelper.TTSStatus.IsStopped -> emptyList()
        }
    }

    //endregion

    //region Notification Management

    /**
     * Cancels the TTS notification.
     *
     * @param context The context for accessing NotificationManager
     */
    fun cancelNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(TTS_NOTIFICATION_ID)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /**
     * Shows or updates the TTS notification.
     *
     * Handles:
     * - Null context gracefully
     * - Permission checking for Android 13+
     * - Error handling for notification posting
     *
     * @param title The title to display
     * @param chapter The current chapter
     * @param icon The book cover icon (optional)
     * @param status The current TTS status
     * @param context The context for showing the notification
     */
    fun notify(
        title: String,
        chapter: UiText,
        icon: Bitmap?,
        status: TTSHelper.TTSStatus,
        context: Context?
    ) {
        if (context == null) return

        // Update playback state in media session
        updatePlaybackState(status)

        val notification = createNotification(title, chapter, icon, status, context)
            ?: return

        if (!hasNotificationPermission(context)) {
            logError(SecurityException("POST_NOTIFICATIONS permission not granted"))
            return
        }

        try {
            NotificationManagerCompat.from(context).notify(TTS_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Handle case where permission was revoked between check and notify
            logError(e)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /**
     * Checks if the app has permission to post notifications.
     *
     * @param context The context for checking permissions
     * @return true if permission is granted or not required (pre-Android 13)
     */
    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    //endregion

    //region Companion Utilities

    /** All supported playback actions for the media session */
    private const val SUPPORTED_PLAYBACK_ACTIONS: Long =
        PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

    //endregion
}

//region Extension Functions

/** Gets the NotificationManager system service */
private val Context.notificationManager: NotificationManager
    get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

/**
 * Extracts KeyEvent from Intent with proper API handling.
 */
private fun Intent.getKeyEventCompat(): KeyEvent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
}

/**
 * Converts TTS status to PlaybackState constant.
 */
private fun TTSHelper.TTSStatus.toPlaybackState(): Int {
    return when (this) {
        TTSHelper.TTSStatus.IsRunning -> PlaybackStateCompat.STATE_PLAYING
        TTSHelper.TTSStatus.IsPaused -> PlaybackStateCompat.STATE_PAUSED
        TTSHelper.TTSStatus.IsStopped -> PlaybackStateCompat.STATE_STOPPED
    }
}