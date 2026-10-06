package com.meshchat.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions a call or a file transfer needs on top of the Bluetooth ones. */
object CallPermissions {

    /** Wi-Fi Direct: NEARBY_WIFI_DEVICES on Android 13+, location on older versions (an OS rule; GPS is not read). */
    private fun wifi(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun forCall(video: Boolean): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (video) add(Manifest.permission.CAMERA)
        add(wifi())
    }

    fun forTransfer(): List<String> = listOf(wifi())

    fun missing(ctx: Context, perms: List<String>): List<String> =
        perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
}
