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

    private val _sosTone = MutableStateFlow<String?>(null)
    /** null = the phone's default alarm sound, "" = silent, otherwise a sound Uri. */
    val sosTone: StateFlow<String?> = _sosTone.asStateFlow()
    fun setSosTone(uri: String?) {
        _sosTone.value = uri
        prefs.edit().apply { if (uri == null) remove("sos_tone") else putString("sos_tone", uri) }.apply()
    }

    private val _messageNotif = MutableStateFlow(true)
    val messageNotifications: StateFlow<Boolean> = _messageNotif.asStateFlow()
    private val _callNotif = MutableStateFlow(true)
    val callNotifications: StateFlow<Boolean> = _callNotif.asStateFlow()
    private val _transferNotif = MutableStateFlow(true)
    val transferNotifications: StateFlow<Boolean> = _transferNotif.asStateFlow()
    private val _callVibrate = MutableStateFlow(true)
    val callVibrate: StateFlow<Boolean> = _callVibrate.asStateFlow()

    /** Announce posts older than this (ms) were cleared by the user and are not stored again when sync re-offers them. */
    @Volatile var announceClearedAt: Long = 0L
        private set

    fun markAnnounceCleared() {
        announceClearedAt = System.currentTimeMillis()
        prefs.edit().putLong("announce_cleared_at", announceClearedAt).apply()
    }

    private val _use5Ghz = MutableStateFlow(false)
    /** Wi-Fi Direct band for calls and file transfers started on this phone: false = 2.4 GHz (works with every phone), true = 5 GHz where both support it. */
    val use5Ghz: StateFlow<Boolean> = _use5Ghz.asStateFlow()
    fun setUse5Ghz(v: Boolean) { _use5Ghz.value = v; prefs.edit().putBoolean("wifi_5ghz", v).apply() }

    private val _sosAlerts = MutableStateFlow(true)
    /** Show alerts for SOS broadcasts from other people. Off = this phone still relays them but stays quiet. */
    val sosAlerts: StateFlow<Boolean> = _sosAlerts.asStateFlow()
    fun setSosAlerts(v: Boolean) { _sosAlerts.value = v; prefs.edit().putBoolean("sos_alerts", v).apply() }

    private val _sosActive = MutableStateFlow(false)
    /** My own SOS is running (survives an app restart so the beacon resumes). */
    val sosActive: StateFlow<Boolean> = _sosActive.asStateFlow()
    fun setSosActive(v: Boolean) { _sosActive.value = v; prefs.edit().putBoolean("sos_active", v).apply() }

    @Volatile var sosIncludeLocation: Boolean = false
        private set
    fun setSosIncludeLocation(v: Boolean) { sosIncludeLocation = v; prefs.edit().putBoolean("sos_include_location", v).apply() }

    private val _pttEnabled = MutableStateFlow(true)
    /** Master switch for receiving push-to-talk (short clips and live sessions). Off = declined silently. */
    val pttEnabled: StateFlow<Boolean> = _pttEnabled.asStateFlow()
    fun setPttEnabled(v: Boolean) { _pttEnabled.value = v; prefs.edit().putBoolean("ptt_enabled", v).apply() }
    private val _pttAutoPlay = MutableStateFlow(true)
    /** Play incoming push-to-talk clips by themselves while the app is open. */
    val pttAutoPlay: StateFlow<Boolean> = _pttAutoPlay.asStateFlow()
    fun setPttAutoPlay(v: Boolean) { _pttAutoPlay.value = v; prefs.edit().putBoolean("ptt_autoplay", v).apply() }
    private val _pttAutoAnswer = MutableStateFlow(false)
    /** Connect incoming live push-to-talk sessions without ringing (microphones stay closed until a button is held). */
    val pttAutoAnswer: StateFlow<Boolean> = _pttAutoAnswer.asStateFlow()
    fun setPttAutoAnswer(v: Boolean) { _pttAutoAnswer.value = v; prefs.edit().putBoolean("ptt_autoanswer", v).apply() }

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _downloadTree.value = prefs.getString("download_tree", null)
        _ringtone.value = if (prefs.contains("ringtone")) prefs.getString("ringtone", "") else null
        _messageNotif.value = prefs.getBoolean("notif_messages", true)
        _callNotif.value = prefs.getBoolean("notif_calls", true)
        _transferNotif.value = prefs.getBoolean("notif_transfers", true)
        _callVibrate.value = prefs.getBoolean("call_vibrate", true)
        _use5Ghz.value = prefs.getBoolean("wifi_5ghz", false)
        _sosTone.value = if (prefs.contains("sos_tone")) prefs.getString("sos_tone", "") else null
        announceClearedAt = prefs.getLong("announce_cleared_at", 0L)
        _sosAlerts.value = prefs.getBoolean("sos_alerts", true)
        _pttEnabled.value = prefs.getBoolean("ptt_enabled", true)
        _pttAutoPlay.value = prefs.getBoolean("ptt_autoplay", true)
        _pttAutoAnswer.value = prefs.getBoolean("ptt_autoanswer", false)
        _sosActive.value = prefs.getBoolean("sos_active", false)
        sosIncludeLocation = prefs.getBoolean("sos_include_location", false)
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
