package com.meshchat.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-chosen options. Stored on this phone only. Call [init] once from Application.onCreate. */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    private val _downloadTree = MutableStateFlow<String?>(null)
    /** SAF tree Uri chosen for received files, or null = Downloads/MeshChat. */
    val downloadTree: StateFlow<String?> = _downloadTree.asStateFlow()

    private val _ringtone = MutableStateFlow<String?>(null)
    /** null = phone's default ringtone, "" = silent, otherwise a ringtone Uri. */
    val ringtone: StateFlow<String?> = _ringtone.asStateFlow()

    private val _messageNotif = MutableStateFlow(true)
    val messageNotifications: StateFlow<Boolean> = _messageNotif.asStateFlow()
    private val _callNotif = MutableStateFlow(true)
    val callNotifications: StateFlow<Boolean> = _callNotif.asStateFlow()
    private val _transferNotif = MutableStateFlow(true)
    val transferNotifications: StateFlow<Boolean> = _transferNotif.asStateFlow()
    private val _callVibrate = MutableStateFlow(true)
    val callVibrate: StateFlow<Boolean> = _callVibrate.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _downloadTree.value = prefs.getString("download_tree", null)
        _ringtone.value = if (prefs.contains("ringtone")) prefs.getString("ringtone", "") else null
        _messageNotif.value = prefs.getBoolean("notif_messages", true)
        _callNotif.value = prefs.getBoolean("notif_calls", true)
        _transferNotif.value = prefs.getBoolean("notif_transfers", true)
        _callVibrate.value = prefs.getBoolean("call_vibrate", true)
    }

    fun setDownloadTree(uri: Uri?) {
        _downloadTree.value = uri?.toString()
        prefs.edit().apply { if (uri == null) remove("download_tree") else putString("download_tree", uri.toString()) }.apply()
    }

    fun setRingtone(uri: String?) {
        _ringtone.value = uri
        prefs.edit().apply { if (uri == null) remove("ringtone") else putString("ringtone", uri) }.apply()
    }

    fun setMessageNotifications(v: Boolean) { _messageNotif.value = v; prefs.edit().putBoolean("notif_messages", v).apply() }
    fun setCallNotifications(v: Boolean) { _callNotif.value = v; prefs.edit().putBoolean("notif_calls", v).apply() }
    fun setTransferNotifications(v: Boolean) { _transferNotif.value = v; prefs.edit().putBoolean("notif_transfers", v).apply() }
    fun setCallVibrate(v: Boolean) { _callVibrate.value = v; prefs.edit().putBoolean("call_vibrate", v).apply() }
}
