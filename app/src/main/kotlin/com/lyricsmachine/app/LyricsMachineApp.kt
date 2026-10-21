package com.lyricsmachine.app

import android.app.Application

class LyricsMachineApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
