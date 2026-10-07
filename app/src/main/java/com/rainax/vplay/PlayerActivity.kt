package com.rainax.vplay

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "rainax.uris"
        const val EXTRA_TITLES = "rainax.titles"
        const val EXTRA_INDEX = "rainax.index"

        private const val SEEK_STEP_MS = 10_000L
        private const val FULL_WIDTH_SEEK_MS = 120_000L
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var indicator: TextView
    private lateinit var unlockButton: ImageButton
    private lateinit var audioManager: AudioManager
    private lateinit var store: PositionStore
    private lateinit var gestureHandler: GestureHandler
    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private val hideIndicator = Runnable { indicator.visibility = View.GONE }
    private val hideUnlock = Runnable { unlockButton.visibility = View.GONE }

    private var locked = false
    private var inPip = false
    private var manualRotation = false
    private var currentUri: String? = null
    private var resizeIndex = 0

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeLabels = intArrayOf(
        R.string.aspect_fit,
        R.string.aspect_fill,
        R.string.aspect_zoom
    )

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addSubtitle(uri)
        }

    private val lockBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showUnlockButton()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        store = PositionStore(this)

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        titleView = findViewById(R.id.titleView)
        indicator = findViewById(R.id.indicator)
        unlockButton = findViewById(R.id.unlockButton)

        onBackPressedDispatcher.addCallback(this, lockBackCallback)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(i.left + dp(12), i.top + dp(8), i.right + dp(12), dp(8))
            insets
        }

        setupButtons()
        setupPlayer()
        setupGestures()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        if (!inPip) player.pause()
        savePosition()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerView.useController = false
            topBar.visibility = View.GONE
            indicator.visibility = View.GONE
            unlockButton.visibility = View.GONE
        } else if (!locked) {
            playerView.useController = true
            playerView.showController()
        }
    }

    // ---------------------------------------------------------------- setup

    private fun setupButtons() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnSubtitle).setOnClickListener {
            subtitlePicker.launch(arrayOf("*/*"))
        }
        findViewById<ImageButton>(R.id.btnAspect).setOnClickListener { cycleAspect() }
        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener { toggleRotation() }
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener { setLocked(true) }
        unlockButton.setOnClickListener { setLocked(false) }

        val btnPip = findViewById<ImageButton>(R.id.btnPip)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            btnPip.setOnClickListener { enterPip() }
        } else {
            btnPip.visibility = View.GONE
        }
    }

    private fun setupPlayer() {
        val (items, startIndex) = buildItems()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_play, Toast.LENGTH_SHORT).show()
            // player must exist for lifecycle callbacks
            player = ExoPlayer.Builder(this).build()
            finish()
            return
        }

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setSelectUndeterminedTextLanguage(true)
            .build()

        playerView.player = player
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility =
                    if (locked || inPip) View.GONE else visibility
            }
        )

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentUri = mediaItem?.mediaId
                titleView.text = mediaItem?.mediaMetadata?.title ?: ""
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentUri?.let { store.clear(it) }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyAutoOrientation(videoSize)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.cannot_play) + " (" + error.errorCodeName + ")",
                    Toast.LENGTH_LONG
                ).show()
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })

        val startKey = items[startIndex].mediaId
        val saved = store.get(startKey)
        val startPos = if (saved > 5_000L) saved - 1_000L else 0L

        currentUri = startKey
        titleView.text = items[startIndex].mediaMetadata.title ?: ""

        player.setMediaItems(items, startIndex, startPos)
        player.prepare()
        player.playWhenReady = true
    }

    private fun setupGestures() {
        gestureHandler = GestureHandler()
        gestureDetector = GestureDetector(this, gestureHandler)
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureHandler.onUp()
            }
            true
        }
    }

    // ---------------------------------------------------------------- media items

    private fun buildItems(): Pair<List<MediaItem>, Int> {
        val uris = intent.getStringArrayListExtra(EXTRA_URIS)
        if (uris != null && uris.isNotEmpty()) {
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES)
            val items = uris.mapIndexed { i, u -> mediaItem(u, titles?.getOrNull(i)) }
            val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.lastIndex)
            return Pair(items, index)
        }
        val data = intent.data
        if (data != null) {
            return Pair(listOf(mediaItem(data.toString(), queryName(data))), 0)
        }
        return Pair(emptyList(), 0)
    }

    private fun mediaItem(uri: String, title: String?): MediaItem {
        val name = title ?: Uri.parse(uri).lastPathSegment ?: "Video"
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
    }

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) return c.getString(idx)
                        }
                    }
            } catch (e: Exception) {
                // fall through
            }
        }
        return uri.lastPathSegment
    }

    // ---------------------------------------------------------------- subtitles

    private fun addSubtitle(uri: Uri) {
        val name = (queryName(uri) ?: "").lowercase()
        val mime = when {
            name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") || name.endsWith(".dfxp") ->
                MimeTypes.APPLICATION_TTML
            else -> null
        }
        if (mime == null) {
            Toast.makeText(this, R.string.subtitle_unsupported, Toast.LENGTH_LONG).show()
            return
        }

        val current = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady

        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updated = current.buildUpon()
            .setSubtitleConfigurations(listOf(subtitle))
            .build()

        player.replaceMediaItem(index, updated)
        player.seekTo(index, position)
        player.playWhenReady = wasPlaying
        Toast.makeText(this, R.string.subtitle_added, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- controls

    private fun cycleAspect() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIndex]
        showIndicator(getString(resizeLabels[resizeIndex]))
    }

    private fun toggleRotation() {
        manualRotation = true
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun applyAutoOrientation(size: VideoSize) {
        if (manualRotation || inPip || size.width <= 0 || size.height <= 0) return
        requestedOrientation = if (size.width >= size.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = player.videoSize
        val w = if (size.width > 0) size.width else 16
        val h = if (size.height > 0) size.height else 9
        val ratio = w.toFloat() / h.toFloat()
        val rational = when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 0.42f -> Rational(42, 100)
            else -> Rational(w, h)
        }
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(rational).build()
            )
        } catch (e: IllegalStateException) {
            // PiP not allowed right now.
        }
    }

    private fun setLocked(value: Boolean) {
        locked = value
        lockBackCallback.isEnabled = value
        if (value) {
            playerView.useController = false
            topBar.visibility = View.GONE
            showUnlockButton()
            showIndicator(getString(R.string.locked))
        } else {
            handler.removeCallbacks(hideUnlock)
            unlockButton.visibility = View.GONE
            playerView.useController = true
            playerView.showController()
            showIndicator(getString(R.string.unlocked))
        }
    }

    private fun showUnlockButton() {
        handler.removeCallbacks(hideUnlock)
        unlockButton.visibility = View.VISIBLE
        handler.postDelayed(hideUnlock, 2500)
    }

    private fun showIndicator(text: String, autoHideMs: Long = 900L) {
        indicator.text = text
        indicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicator)
        if (autoHideMs > 0) handler.postDelayed(hideIndicator, autoHideMs)
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration
        var target = player.currentPosition + deltaMs
        target = if (duration != C.TIME_UNSET && duration > 0) {
            target.coerceIn(0L, duration)
        } else {
            target.coerceAtLeast(0L)
        }
        player.seekTo(target)
        showIndicator(if (deltaMs > 0) "+10s" else "−10s", 600L)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun savePosition() {
        val uri = currentUri ?: return
        val position = player.currentPosition
        val duration = player.duration
        if (duration != C.TIME_UNSET && position > 5_000L && position < duration - 10_000L) {
            store.put(uri, position)
        } else {
            store.clear(uri)
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun setBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    // ---------------------------------------------------------------- gestures

    private inner class GestureHandler : GestureDetector.SimpleOnGestureListener() {

        private val modeNone = 0
        private val modeSeek = 1
        private val modeBrightness = 2
        private val modeVolume = 3

        private var mode = modeNone
        private var startBrightness = 0.5f
        private var startVolume = 0
        private var startPosition = 0L
        private var seekTarget = -1L
        private var longPressActive = false
        private var speedBeforeLongPress = 1f

        override fun onDown(e: MotionEvent): Boolean {
            mode = modeNone
            seekTarget = -1L
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (inPip) return true
            if (locked) {
                showUnlockButton()
            } else if (playerView.isControllerFullyVisible) {
                playerView.hideController()
            } else {
                playerView.showController()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked || inPip) return true
            val w = playerView.width.toFloat()
            when {
                e.x < w / 3f -> seekBy(-SEEK_STEP_MS)
                e.x > w * 2f / 3f -> seekBy(SEEK_STEP_MS)
                else -> togglePlay()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (locked || inPip || mode != modeNone) return
            longPressActive = true
            speedBeforeLongPress = player.playbackParameters.speed
            player.setPlaybackSpeed(2f)
            showIndicator(getString(R.string.speed_2x), 0L)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (locked || inPip || longPressActive || e1 == null) return false
            val w = playerView.width.toFloat()
            val h = playerView.height.toFloat()
            if (w <= 0f || h <= 0f) return false

            if (mode == modeNone) {
                mode = if (abs(e2.x - e1.x) > abs(e2.y - e1.y)) {
                    modeSeek
                } else if (e1.x < w / 2f) {
                    modeBrightness
                } else {
                    modeVolume
                }
                when (mode) {
                    modeSeek -> startPosition = player.currentPosition
                    modeBrightness -> startBrightness = currentBrightness()
                    modeVolume -> startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
            }

            when (mode) {
                modeSeek -> {
                    val duration = player.duration
                    if (duration == C.TIME_UNSET || duration <= 0L) return true
                    val delta = ((e2.x - e1.x) / w * FULL_WIDTH_SEEK_MS).toLong()
                    seekTarget = (startPosition + delta).coerceIn(0L, duration)
                    val sign = if (delta >= 0) "+" else "−"
                    showIndicator(
                        Fmt.time(seekTarget) + "  (" + sign + Fmt.time(abs(delta)) + ")",
                        0L
                    )
                }
                modeBrightness -> {
                    val value = (startBrightness + (e1.y - e2.y) / h * 1.3f).coerceIn(0.02f, 1f)
                    setBrightness(value)
                    showIndicator(getString(R.string.brightness, (value * 100).roundToInt()), 0L)
                }
                modeVolume -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val value = (startVolume + (e1.y - e2.y) / h * max * 1.3f)
                        .roundToInt().coerceIn(0, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                    val percent = if (max > 0) value * 100 / max else 0
                    showIndicator(getString(R.string.volume, percent), 0L)
                }
            }
            return true
        }

        fun onUp() {
            if (mode == modeSeek && seekTarget >= 0L) {
                player.seekTo(seekTarget)
            }
            if (longPressActive) {
                player.setPlaybackSpeed(speedBeforeLongPress)
                longPressActive = false
            }
            if (indicator.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideIndicator)
                handler.postDelayed(hideIndicator, 500)
            }
            mode = modeNone
            seekTarget = -1L
        }
    }
}
