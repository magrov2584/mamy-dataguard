package com.mamy.dataguard

import android.graphics.drawable.Drawable

/**
 * Représente une application installée sur l'appareil, avec ses règles
 * de pare-feu associées (bloquée en Wi-Fi et/ou en Data Mobile).
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val uid: Int,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    var blockOnWifi: Boolean = false,
    var blockOnMobile: Boolean = false
)
