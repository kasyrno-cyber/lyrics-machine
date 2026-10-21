package com.lyricsmachine.app

import android.app.Application
import com.lyricsmachine.app.speech.ModelSpec

class LyricsMachineApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        // Starts the background check of downloaded models now, so the first tap on a song doesn't wait for it.
        container.modelManager.state(ModelSpec.BASE)
        container.modelManager.state(ModelSpec.TINY)
    }
}
