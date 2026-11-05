package com.flipdesk.app

import android.app.Application
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.display.BigScreen
import com.flipdesk.app.net.Hub
import com.flipdesk.app.service.ConnectionService
import com.flipdesk.app.util.Haptics

class FlipDeskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Haptics.init(this)
        ConnectionService.createChannel(this)
        Hub.init(this)
        BigScreen.init(this)
    }
}
