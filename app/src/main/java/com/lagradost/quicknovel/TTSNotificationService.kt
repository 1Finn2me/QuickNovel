package com.lagradost.quicknovel

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import androidx.media.session.MediaButtonReceiver
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.UiText
import com.lagradost.quicknovel.ui.txt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that manages TTS playback for the novel reader.
 *
 * Features:
 * - Foreground service with media-style notification
 * - Audio focus management (pauses for other audio, phone calls)
 * - Headphone/Bluetooth disconnect detection
 * - Sleep timer functionality
 * - Wake lock for background playback
 * - Service binding for Activity communication
 * - Automatic error recovery
 * - Process death survival
 *
 * Lifecycle:
 * 1. Activity calls [TTSNotificationService.start] with ViewModel
 * 2. Service starts in foreground with notification
 * 3. Service manages TTS playback via ViewModel
 * 4. Service stops when TTS ends or user stops
 *
 * @see TTSNotifications
 * @see ReadActivityViewModel
 */
class TTSNotificationService : Service() {

    //region Companion Object

    companion object {
        private const val TAG = "TTSNotificationService"

        // Intent actions
        const val ACTION_START = "com.lagradost.quicknovel.ACTION_TTS_START"
        const val ACTION_STOP = "com.lagradost.quicknovel.ACTION_TTS_STOP"
        const val ACTION_SET_SLEEP_TIMER = "com.lagradost.quicknovel.ACTION_SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "com.lagradost.quicknovel.ACTION_CANCEL_SLEEP_TIMER"

        // Intent extras
        const val EXTRA_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"

        // Service state
        private var _viewModel: WeakReference<ReadActivityViewModel> = WeakReference(null)
        private val _isRunning = AtomicBoolean(false)
        private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)

        val viewModel: ReadActivityViewModel?
            get() = _viewModel.get()

        val isRunning: Boolean
            get() = _isRunning.get()

        val serviceState: StateFlow<ServiceState>
            get() = _serviceState.asStateFlow()

        /**
         * Starts the TTS service with the given ViewModel.
         *
         * @param viewModel The ViewModel managing TTS state
         * @param context The context to start the service from
         */
        @MainThread
        fun start(viewModel: ReadActivityViewModel, context: Context) {
            if (_isRunning.get() && _viewModel.get() === viewModel) {
                // Already running with same ViewModel
                return
            }

            _viewModel = WeakReference(viewModel)
            _serviceState.value = ServiceState.Starting

            val intent = Intent(context, TTSNotificationService::class.java).apply {
                action = ACTION_START
            }

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                logError(e)
                _serviceState.value = ServiceState.Error(e.message ?: "Failed to start service")
                showToast(R.string.tts_service_start_error)
            }
        }

        /**
         * Stops the TTS service.
         *
         * @param context The context to stop the service from
         */
        @MainThread
        fun stop(context: Context) {
            val intent = Intent(context, TTSNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Sets a sleep timer to automatically stop TTS.
         *
         * @param context The context
         * @param minutes Minutes until auto-stop (0 to cancel)
         */
        fun setSleepTimer(context: Context, minutes: Int) {
            val intent = Intent(context, TTSNotificationService::class.java).apply {
                action = if (minutes > 0) ACTION_SET_SLEEP_TIMER else ACTION_CANCEL_SLEEP_TIMER
                putExtra(EXTRA_SLEEP_TIMER_MINUTES, minutes)
            }
            context.startService(intent)
        }

        /**
         * Creates a PendingIntent to stop the service.
         */
        fun getStopPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TTSNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            return PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    //endregion

    //region Service State

    /**
     * Represents the current state of the TTS service.
     */
    sealed class ServiceState {
        object Stopped : ServiceState()
        object Starting : ServiceState()
        object Running : ServiceState()
        object Paused : ServiceState()
        data class Error(val message: String) : ServiceState()
        data class SleepTimerActive(val remainingMinutes: Int) : ServiceState()
    }

    //endregion

    //region Private State

    private var currentJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Audio state
    private var hadAudioFocus = false
    private var wasPlayingBeforeAudioLoss = false
    private var wasPlayingBeforePhoneCall = false
    private var wasPlayingBeforeHeadphoneDisconnect = false

    // Phone state listener for API < 31
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // Telephony callback for API >= 31
    @RequiresApi(Build.VERSION_CODES.S)
    private var telephonyCallback: TelephonyCallback? = null

    // Receivers
    private var headphoneReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    //endregion

    //region Lifecycle

    override fun onCreate() {
        super.onCreate()
        _isRunning.set(true)

        registerHeadphoneReceiver()
        registerBluetoothReceiver()
        registerPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0)
                handleSetSleepTimer(minutes)
            }
            ACTION_CANCEL_SLEEP_TIMER -> handleCancelSleepTimer()
            else -> {
                // Handle media button intent
                TTSNotifications.mediaSession?.let { session ->
                    MediaButtonReceiver.handleIntent(session, intent)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return TTSBinder()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App was swiped away from recents
        // Optionally keep playing or stop
        // For now, we keep playing - user can stop via notification
        super.onTaskRemoved(rootIntent)
    }

    //endregion

    //region Binder

    /**
     * Binder for Activity communication with the service.
     */
    inner class TTSBinder : Binder() {

        /**
         * Gets the current ViewModel if available.
         */
        fun getViewModel(): ReadActivityViewModel? = viewModel

        /**
         * Checks if the service is currently playing.
         */
        fun isPlaying(): Boolean {
            return viewModel?.ttsStatus?.value == TTSHelper.TTSStatus.IsRunning
        }

        /**
         * Sets the sleep timer.
         */
        fun setSleepTimer(minutes: Int) {
            handleSetSleepTimer(minutes)
        }

        /**
         * Cancels the sleep timer.
         */
        fun cancelSleepTimer() {
            handleCancelSleepTimer()
        }

        /**
         * Gets the remaining sleep timer time in minutes.
         */
        fun getSleepTimerRemaining(): Int? {
            val state = _serviceState.value
            return if (state is ServiceState.SleepTimerActive) {
                state.remainingMinutes
            } else {
                null
            }
        }
    }

    //endregion

    //region Start/Stop Handling

    private fun handleStart() {
        currentJob?.cancel()

        val vm = viewModel
        if (vm == null) {
            showErrorAndStop(getString(R.string.tts_error_no_viewmodel))
            return
        }

        // Initialize media session
        TTSNotifications.initializeMediaSession(vm, vm.book, this)

        // Create initial notification
        val notification = TTSNotifications.createNotification(
            title = vm.book.title(),
            chapter = getCurrentChapterText(vm),
            icon = vm.book.poster(),
            status = TTSHelper.TTSStatus.IsRunning,
            context = this
        )

        if (notification == null) {
            showErrorAndStop(getString(R.string.tts_error_notification_failed))
            return
        }

        try {
            startForeground(TTSNotifications.TTS_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            logError(e)
            showErrorAndStop(getString(R.string.tts_error_foreground_failed))
            return
        }

        // Acquire wake lock for background playback
        acquireWakeLock()

        // Request audio focus
        if (!requestAudioFocus()) {
            showToast(R.string.tts_warning_audio_focus)
            // Continue anyway - not critical
        }

        _serviceState.value = ServiceState.Running

        // Start TTS in background
        currentJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    vm.startTTSThread()
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                logError(e)
                handleTTSError(e)
            } finally {
                // TTS finished naturally or with error
                withContext(Dispatchers.Main) {
                    handleStop()
                }
            }
        }

        // Observe TTS status changes for notification updates
        observeTTSStatus(vm)
    }

    private fun handleStop() {
        _serviceState.value = ServiceState.Stopped

        currentJob?.cancel()
        currentJob = null

        sleepTimerJob?.cancel()
        sleepTimerJob = null

        releaseWakeLock()
        abandonAudioFocus()

        stopForegroundCompat()
        TTSNotifications.cancelNotification(this)
        TTSNotifications.releaseMediaSession()

        _viewModel.clear()

        stopSelf()
    }

    private fun showErrorAndStop(message: String) {
        _serviceState.value = ServiceState.Error(message)
        showToast(message)

        // Still need to start foreground briefly before stopping
        val errorNotification = TTSNotifications.createNotification(
            title = getString(R.string.error),
            chapter = txt(message),
            icon = null,
            status = TTSHelper.TTSStatus.IsRunning,
            context = this
        )

        try {
            if (errorNotification != null) {
                startForeground(TTSNotifications.TTS_NOTIFICATION_ID, errorNotification)
            }
        } catch (e: Exception) {
            logError(e)
        }

        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    //endregion

    //region TTS Status Observation

    private fun observeTTSStatus(vm: ReadActivityViewModel) {
        serviceScope.launch {
            vm.ttsStatus.asFlow().collectLatest { status: TTSHelper.TTSStatus? ->
                // Handle null case (initial state)
                val safeStatus = status ?: TTSHelper.TTSStatus.IsStopped

                updateNotification(vm, safeStatus)
                TTSNotifications.updatePlaybackState(safeStatus)

                when (safeStatus) {
                    TTSHelper.TTSStatus.IsStopped -> {
                        handleStop()
                    }
                    TTSHelper.TTSStatus.IsPaused -> {
                        _serviceState.value = ServiceState.Paused
                    }
                    TTSHelper.TTSStatus.IsRunning -> {
                        if (_serviceState.value !is ServiceState.SleepTimerActive) {
                            _serviceState.value = ServiceState.Running
                        }
                    }
                }
            }
        }
    }

    private fun updateNotification(vm: ReadActivityViewModel, status: TTSHelper.TTSStatus) {
        val notification = TTSNotifications.createNotification(
            title = vm.book.title(),
            chapter = getCurrentChapterText(vm),
            icon = vm.book.poster(),
            status = status,
            context = this
        ) ?: return

        TTSNotifications.notify(
            title = vm.book.title(),
            chapter = getCurrentChapterText(vm),
            icon = vm.book.poster(),
            status = status,
            context = this
        )
    }

    private fun getCurrentChapterText(vm: ReadActivityViewModel): UiText {
        return try {
            // Access the chapter title from the internal list
            // Using reflection or a getter if chaptersTitlesInternal is private
            // Based on your ViewModel, currentIndex and chapterTile are available
            vm.chapterTile.value ?: txt(R.string.chapter_format, (vm.currentIndex + 1).toString())
        } catch (e: Exception) {
            txt(R.string.chapter_format, (vm.currentIndex + 1).toString())
        }
    }

    //endregion

    //region Sleep Timer

    private fun handleSetSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            handleCancelSleepTimer()
            return
        }

        sleepTimerJob?.cancel()

        _serviceState.value = ServiceState.SleepTimerActive(minutes)
        showToast(getString(R.string.sleep_timer_set, minutes))

        sleepTimerJob = serviceScope.launch {
            var remainingMinutes = minutes

            while (remainingMinutes > 0) {
                delay(TimeUnit.MINUTES.toMillis(1))
                remainingMinutes--
                _serviceState.value = ServiceState.SleepTimerActive(remainingMinutes)
            }

            // Timer expired - stop playback
            showToast(R.string.sleep_timer_expired)
            viewModel?.stopTTS()
            handleStop()
        }
    }

    private fun handleCancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null

        val wasTimerActive = _serviceState.value is ServiceState.SleepTimerActive
        if (wasTimerActive) {
            _serviceState.value = ServiceState.Running
            showToast(R.string.sleep_timer_cancelled)
        }
    }

    //endregion

    //region Audio Focus

    private fun requestAudioFocus(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hadAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hadAudioFocus
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }

        hadAudioFocus = false
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss - stop
                wasPlayingBeforeAudioLoss = viewModel?.ttsStatus?.value == TTSHelper.TTSStatus.IsRunning
                viewModel?.pauseTTS()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss (phone call, navigation, etc.) - pause
                wasPlayingBeforeAudioLoss = viewModel?.ttsStatus?.value == TTSHelper.TTSStatus.IsRunning
                if (wasPlayingBeforeAudioLoss) {
                    viewModel?.pauseTTS()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Could lower volume, but for TTS it's better to pause
                wasPlayingBeforeAudioLoss = viewModel?.ttsStatus?.value == TTSHelper.TTSStatus.IsRunning
                if (wasPlayingBeforeAudioLoss) {
                    viewModel?.pauseTTS()
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained focus - resume if we were playing before
                if (wasPlayingBeforeAudioLoss) {
                    viewModel?.playTTS()
                    wasPlayingBeforeAudioLoss = false
                }
            }
        }
    }

    //endregion

    //region Headphone/Bluetooth Handling

    private fun registerHeadphoneReceiver() {
        headphoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    // Headphones unplugged - pause
                    wasPlayingBeforeHeadphoneDisconnect =
                        viewModel?.ttsStatus?.value == TTSHelper.TTSStatus.IsRunning

                    if (wasPlayingBeforeHeadphoneDisconnect) {
                        viewModel?.pauseTTS()
                        showToast(R.string.tts_paused_headphones_disconnected)
                    }
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(headphoneReceiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun registerBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        // Bluetooth device disconnected
                        val wasPlaying = viewModel?.ttsStatus?.value == TTSHelper.TTSStatus.IsRunning
                        if (wasPlaying) {
                            viewModel?.pauseTTS()
                            showToast(R.string.tts_paused_bluetooth_disconnected)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }

        try {
            registerReceiver(bluetoothReceiver, filter)
        } catch (e: Exception) {
            // Bluetooth permission might not be granted
            logError(e)
        }
    }

    //endregion

    //region Phone State Handling

    private fun registerPhoneStateListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handlePhoneStateChanged(state)
                }
            }

            try {
                telephonyManager.registerTelephonyCallback(
                    mainExecutor,
                    telephonyCallback!!
                )
            } catch (e: SecurityException) {
                // Permission not granted - not critical
                logError(e)
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handlePhoneStateChanged(state)
                }
            }

            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: SecurityException) {
                logError(e)
            }
        }
    }

    private fun handlePhoneStateChanged(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Phone ringing or in call - pause
                wasPlayingBeforePhoneCall =
                    viewModel?.ttsStatus?.value == TTSHelper.TTSStatus.IsRunning

                if (wasPlayingBeforePhoneCall) {
                    viewModel?.pauseTTS()
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended - resume if we were playing
                if (wasPlayingBeforePhoneCall) {
                    // Small delay to let audio system settle
                    serviceScope.launch {
                        delay(500)
                        viewModel?.playTTS()
                        wasPlayingBeforePhoneCall = false
                    }
                }
            }
        }
    }

    private fun unregisterPhoneStateListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { callback ->
                telephonyManager.unregisterTelephonyCallback(callback)
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
    }

    //endregion

    //region Wake Lock

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "QuickNovel:TTSWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    //endregion

    //region Error Recovery

    private suspend fun handleTTSError(error: Exception) {
        _serviceState.value = ServiceState.Error(error.message ?: "Unknown TTS error")
        logError(error)

        // Notify user about the error
        withContext(Dispatchers.Main) {
            showToast(R.string.tts_error_recovery_failed)
        }

        // We can't recover automatically, stop the service
        // User will need to restart TTS manually
        handleStop()
    }

//endregion

    //endregion

    //region Cleanup

    private fun cleanup() {
        _isRunning.set(false)
        _serviceState.value = ServiceState.Stopped

        currentJob?.cancel()
        sleepTimerJob?.cancel()
        serviceScope.cancel()

        try {
            headphoneReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            logError(e)
        }

        try {
            bluetoothReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            logError(e)
        }

        unregisterPhoneStateListener()
        abandonAudioFocus()
        releaseWakeLock()

        TTSNotifications.releaseMediaSession()
        TTSNotifications.cancelNotification(this)

        _viewModel.clear()
    }

    //endregion
}