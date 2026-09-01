package com.blindreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class ReaderService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ReaderService"
        private const val CHANNEL_ID = "reader"
        private const val NOTIF_ID = 1
        private const val BATTERY_CHANNEL_ID = "battery"
        private const val BATTERY_NOTIF_ID = 2
        private const val LOW_BATTERY_THRESHOLD = 20

        private const val ACTION_PLAY = "com.blindreader.PLAY"
        private const val ACTION_PAUSE = "com.blindreader.PAUSE"
        private const val ACTION_RESTART = "com.blindreader.RESTART"
        private const val ACTION_PREV = "com.blindreader.PREV"
        private const val ACTION_NEXT = "com.blindreader.NEXT"
        private const val ACTION_NEXT_PAGE = "com.blindreader.NEXT_PAGE"
        private const val ACTION_SPEED_UP = "com.blindreader.SPEED_UP"
        private const val ACTION_SPEED_DOWN = "com.blindreader.SPEED_DOWN"
        private const val ACTION_VOL_UP = "com.blindreader.VOL_UP"
        private const val ACTION_VOL_DOWN = "com.blindreader.VOL_DOWN"
        private const val ACTION_VOICE = "com.blindreader.VOICE"
        private const val ACTION_STOP = "com.blindreader.STOP"

        private const val EXTRA_URI = "uri"
        private const val EXTRA_COMMAND = "command"
        private const val PREFS_NAME = "reader_positions"
        private const val KEY_PREFIX = "pos_"

        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 2.0f
        private const val SPEED_STEP = 0.1f
        private const val PAGE_SIZE = 10
        private const val CONFIRM_TIMEOUT_MS = 5000L

        var instance: ReaderService? = null
        var onOpenFile: (() -> Unit)? = null

        fun play(context: Context, uri: Uri) {
            val i = Intent(context, ReaderService::class.java).setAction(ACTION_PLAY)
            i.putExtra(EXTRA_URI, uri)
            context.startForegroundService(i)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun restart(context: Context) = send(context, ACTION_RESTART)
        fun prev(context: Context) = send(context, ACTION_PREV)
        fun next(context: Context) = send(context, ACTION_NEXT)
        fun nextPage(context: Context) = send(context, ACTION_NEXT_PAGE)
        fun speedUp(context: Context) = send(context, ACTION_SPEED_UP)
        fun speedDown(context: Context) = send(context, ACTION_SPEED_DOWN)
        fun volUp(context: Context) = send(context, ACTION_VOL_UP)
        fun volDown(context: Context) = send(context, ACTION_VOL_DOWN)
        fun nextVoice(context: Context) = send(context, ACTION_VOICE)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            val i = Intent(context, ReaderService::class.java).setAction(action)
            context.startService(i)
        }
    }

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var sentences: List<String> = emptyList()
    private var currentIndex = 0
    private var isPlaying = false
    private var speed = 1.0f
    private var voices: List<Voice> = emptyList()
    private var polishVoices: List<Voice> = emptyList()
    private var voiceIndex = 0
    private var batteryLowNotified = false
    private var pendingCommand: String? = null
    private var pendingCommandDescription: String? = null
    private var wasPlayingBeforeConfirm = false
    private var resumeAfterFeedback = false
    private val confirmHandler = android.os.Handler(Looper.getMainLooper())
    private val confirmTimeout = Runnable {
        if (pendingCommand != null) {
            pendingCommand = null
            pendingCommandDescription = null
            if (wasPlayingBeforeConfirm) {
                resume()
            }
            playBeep()
        }
    }
    private var currentUri: String? = null
    private var loadedUri: String? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                val pct = level * 100 / scale
                checkBattery(pct)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        tts = TextToSpeech(this, this)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        instance = null
        unregisterReceiver(batteryReceiver)
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun setDocument(uri: Uri) {
        currentUri = uri.toString()
        pendingCommand = null
        confirmHandler.removeCallbacks(confirmTimeout)
        loadDocument(uri)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
                if (uri != null) {
                    startForegroundCompat()
                    isPlaying = true
                    loadDocument(uri)
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_RESTART -> restart()
            ACTION_PREV -> prevSentence()
            ACTION_NEXT -> nextSentence()
            ACTION_NEXT_PAGE -> nextPage()
            ACTION_SPEED_UP -> changeSpeed(SPEED_STEP)
            ACTION_SPEED_DOWN -> changeSpeed(-SPEED_STEP)
            ACTION_VOL_UP -> changeVolume(1)
            ACTION_VOL_DOWN -> changeVolume(-1)
            ACTION_VOICE -> nextVoice()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            tts.setSpeechRate(speed)
            voices = tts.voices?.toList() ?: emptyList()
            polishVoices = voices.filter { v ->
                v.locale.language.equals("pl", true) ||
                    v.locale.language.equals("pol", true)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "sentence") {
                        runOnMain { onSentenceDone() }
                    } else if (utteranceId == "feedback") {
                        runOnMain { onFeedbackDone() }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
            ttsReady = true
            if (isPlaying) playCurrent()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    private fun loadDocument(uri: Uri) {
        currentUri = uri.toString()
        loadedUri = currentUri
        Thread {
            try {
                val text = DocumentParser.parse(this, uri)
                sentences = splitSentences(TextPreprocessor.process(text))
                currentIndex = prefs.getInt(KEY_PREFIX + currentUri, 0).coerceIn(0, sentences.size - 1)
                runOnMain { if (isPlaying) playCurrent() }
            } catch (e: Exception) {
                Log.e(TAG, "Błąd wczytywania dokumentu", e)
                runOnMain { speak("Nie udało się wczytać dokumentu") }
            }
        }.start()
    }

    private fun restart() {
        if (sentences.isEmpty()) return
        currentIndex = 0
        savePosition()
        playCurrent()
    }

    private fun nextPage() {
        if (sentences.isEmpty()) return
        currentIndex = (currentIndex + PAGE_SIZE).coerceAtMost(sentences.size - 1)
        playCurrent()
    }

    private fun nextVoice() {
        if (polishVoices.isEmpty()) {
            speak("Brak polskich lektorów")
            return
        }
        voiceIndex = (voiceIndex + 1) % polishVoices.size
        tts.voice = polishVoices[voiceIndex]
        val name = polishVoices[voiceIndex].name.substringAfterLast('-').replace('_', ' ')
        speak("Lektor: $name")
    }

    private fun playCurrent() {
        if (!ttsReady || sentences.isEmpty()) return
        if (currentIndex >= sentences.size) {
            isPlaying = false
            updateNotification()
            return
        }
        isPlaying = true
        val text = sentences[currentIndex]
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sentence")
        updateNotification()
    }

    private fun onSentenceDone() {
        if (!isPlaying) return
        currentIndex++
        savePosition()
        if (currentIndex < sentences.size) {
            playCurrent()
        } else {
            isPlaying = false
            updateNotification()
        }
    }

    private fun onFeedbackDone() {
        if (resumeAfterFeedback) {
            resumeAfterFeedback = false
            if (!isPlaying && sentences.isNotEmpty()) {
                resume()
            }
        }
    }

    private fun pause() {
        if (isPlaying) {
            isPlaying = false
            tts.stop()
            savePosition()
            updateNotification()
        }
    }

    private fun resume() {
        if (!isPlaying && sentences.isNotEmpty()) {
            isPlaying = true
            playCurrent()
        }
    }

    private fun prevSentence() {
        if (sentences.isEmpty()) return
        if (currentIndex > 0) currentIndex--
        playCurrent()
    }

    private fun nextSentence() {
        if (sentences.isEmpty()) return
        if (currentIndex < sentences.size - 1) currentIndex++
        playCurrent()
    }

    private fun savePosition() {
        val uri = currentUri ?: return
        prefs.edit().putInt(KEY_PREFIX + uri, currentIndex).apply()
    }

    private fun splitSentences(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun changeSpeed(delta: Float) {
        speed = (speed + delta).coerceIn(MIN_SPEED, MAX_SPEED)
        tts.setSpeechRate(speed)
        speak("Prędkość ${String.format(Locale.US, "%.1f", speed)}")
    }

    /**
     * Obsługa komendy: każde naciśnięcie wstrzymuje czytanie (jeśli trwa)
     * i odtwarza słowny opis funkcji. Ponowne naciśnięcie tego samego przycisku
     * w ciągu 5 sekund wykonuje funkcję. Naciśnięcie innego przycisku odtwarza
     * jego opis od nowa. Po 5 sekundach bezczynności wraca do czytania (jeśli
     * było przerwane) lub czeka na następną akcję.
     */
    fun handleCommand(command: String, description: String) {
        if (pendingCommand == command) {
            confirmHandler.removeCallbacks(confirmTimeout)
            pendingCommand = null
            pendingCommandDescription = null
            playConfirmBeep()
            executeCommand(command)
        } else {
            val wasPlaying = isPlaying
            pause()
            resumeAfterFeedback = false
            pendingCommand = command
            pendingCommandDescription = description
            wasPlayingBeforeConfirm = wasPlaying
            confirmHandler.removeCallbacks(confirmTimeout)
            confirmHandler.postDelayed(confirmTimeout, CONFIRM_TIMEOUT_MS)
            speak(description)
        }
    }

    private fun executeCommand(command: String) {
        when (command) {
            "play" -> {
                val uri = currentUri
                if (loadedUri != currentUri && uri != null) {
                    isPlaying = true
                    loadDocument(Uri.parse(uri))
                } else if (isPlaying) {
                    restart()
                } else {
                    resume()
                }
            }
            "pause" -> pause()
            "restart" -> restart()
            "prev" -> prevSentence()
            "next" -> nextSentence()
            "next_page" -> nextPage()
            "speed_up" -> {
                resumeAfterFeedback = wasPlayingBeforeConfirm
                changeSpeed(SPEED_STEP)
            }
            "speed_down" -> {
                resumeAfterFeedback = wasPlayingBeforeConfirm
                changeSpeed(-SPEED_STEP)
            }
            "vol_up" -> {
                resumeAfterFeedback = wasPlayingBeforeConfirm
                changeVolume(1)
            }
            "vol_down" -> {
                resumeAfterFeedback = wasPlayingBeforeConfirm
                changeVolume(-1)
            }
            "voice" -> {
                resumeAfterFeedback = wasPlayingBeforeConfirm
                nextVoice()
            }
            "open" -> onOpenFile?.invoke()
        }
    }

    private fun changeVolume(direction: Int) {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        val next = (current + direction).coerceIn(0, max)
        audioManager.setStreamVolume(stream, next, 0)
        speak("Głośność $next")
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "feedback")
    }

    private fun playBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            tg.release()
        } catch (e: Exception) {
            Log.e(TAG, "Błąd beep", e)
        }
    }

    private fun playConfirmBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            tg.release()
        } catch (e: Exception) {
            Log.e(TAG, "Błąd beep", e)
        }
    }

    private fun checkBattery(level: Int) {
        if (level <= LOW_BATTERY_THRESHOLD && !batteryLowNotified) {
            batteryLowNotified = true
            showBatteryNotification(level)
            speak("Uwaga, niski poziom baterii: $level procent")
        } else if (level > LOW_BATTERY_THRESHOLD) {
            batteryLowNotified = false
        }
    }

    private fun showBatteryNotification(level: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = Notification.Builder(this, BATTERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.battery_low_title))
            .setContentText("${getString(R.string.battery_low_text)}: $level%")
            .setAutoCancel(true)
            .build()
        nm.notify(BATTERY_NOTIF_ID, notification)
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val batteryChannel = NotificationChannel(
            BATTERY_CHANNEL_ID,
            getString(R.string.battery_low_title),
            NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(batteryChannel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1, Intent(this, ReaderService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 3, Intent(this, ReaderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isPlaying) getString(R.string.notification_playing)
        else getString(R.string.notification_paused)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(title)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.pause), pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.pause), stopIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }
}
