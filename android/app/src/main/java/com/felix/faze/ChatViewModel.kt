package com.felix.faze

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** The screen's handle on the app-wide [FazeCore]; the conversation outlives the screen because the core does. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val core: FazeCore = (app as FazeApp).core
}
