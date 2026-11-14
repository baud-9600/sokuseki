package de.asdfghasdfgh.muditracks

import android.app.Application

class MuditracksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StatusStore.init(this)
    }
}
