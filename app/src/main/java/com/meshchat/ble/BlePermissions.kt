package com.meshchat.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Runtime permissions, by Android version:
 *
 *  API 31+ (Android 12+): BLUETOOTH_SCAN (find nodes), BLUETOOTH_ADVERTISE (announce our Node ID),
 *                         BLUETOOTH_CONNECT (GATT links). No location permission: BLUETOOTH_SCAN is declared
 *                         `neverForLocation` in the manifest because MeshChat never derives position from scans.
 *  API 30 and below:      the OS ties BLE scan results to ACCESS_FINE_LOCATION, and the system Location switch
 *                         must be ON. The mesh itself never reads or transmits GPS; this is purely an OS requirement.
 *                         (Location is read ONLY when the user explicitly shares it in a chat; see LocationHelper.)
 *  API 33+:               POST_NOTIFICATIONS (foreground-service + message notifications). Optional: the mesh
 *                         still works if the user denies it.
 */
object BlePermissions {

    /** Without all of these the mesh cannot run. */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun optional(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun hasRequired(context: Context): Boolean = required().all { has(context, it) }

    fun hasNotificationPermission(context: Context): Boolean =
        optional().all { has(context, it) }

    /** Android 11 and below only: BLE scanning silently returns nothing while the Location switch is off. */
    fun needsLocationSwitch(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun isLocationSwitchOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
