package com.meshchat

import android.app.Application
import android.util.Log
import com.meshchat.core.MeshLog
import com.meshchat.repo.MeshRepository
import com.meshchat.service.Notifier

class MeshChatApp : Application() {
    lateinit var repo: MeshRepository
        private set

    override fun onCreate() {
        super.onCreate()
        MeshLog.sink = { Log.i("MeshBle", it) }   // adb logcat -s MeshBle
        com.meshchat.data.AppSettings.init(this)
        Notifier.createChannels(this)
        repo = MeshRepository(this)
    }
}
