package com.felix.faze

import android.app.Application

class FazeApp : Application() {
    lateinit var core: FazeCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = FazeCore(this)
    }
}
