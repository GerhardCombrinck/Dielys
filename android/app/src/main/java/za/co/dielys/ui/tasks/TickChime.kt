package za.co.dielys.ui.tasks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import dagger.hilt.android.qualifiers.ApplicationContext
import za.co.dielys.R
import za.co.dielys.data.local.UiPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The short chime for ticking an item off. Its own interface so
 * [TaskListViewModel] can be tested on the JVM, where there is no audio to play.
 */
interface TickChime {
    fun play()
}

/**
 * Plays `res/raw/tick_chime.wav` (drawn by `docs/sound/make_chime.py`, the same
 * file the web app plays). A [SoundPool] rather than a `MediaPlayer`: it is
 * decoded once up front, so the chime starts on the tap instead of a beat after.
 *
 * Quiet unless the phone's ringer is on — on silent or vibrate it would be a
 * sound the person has already said they do not want — and off entirely when
 * the setting is ([UiPrefs.tickSound]).
 */
@Singleton
class SoundPoolTickChime
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val prefs: UiPrefs,
    ) : TickChime {
        private val pool =
            SoundPool
                .Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).build()

        // Loading is asynchronous; a tick in the few milliseconds before it is
        // ready just goes without a sound rather than waiting for one.
        @Volatile private var loaded = false

        private val soundId: Int =
            pool.run {
                setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
                load(context, R.raw.tick_chime, 1)
            }

        override fun play() {
            if (!prefs.tickSound.value || !loaded) return
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            pool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }
