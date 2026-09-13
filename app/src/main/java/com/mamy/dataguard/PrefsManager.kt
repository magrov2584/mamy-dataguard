package com.mamy.dataguard

import android.content.Context
import android.content.SharedPreferences

/**
 * Stocke, par nom de paquet, les règles de blocage choisies par l'utilisateur :
 *  - blockOnWifi  : bloquer l'app quand l'appareil est connecté en Wi-Fi
 *  - blockOnMobile: bloquer l'app quand l'appareil est connecté en Data Mobile
 *
 * Les règles sont enregistrées sous forme de String encodée "wifi:mobile" (ex: "1:0").
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setRule(packageName: String, blockOnWifi: Boolean, blockOnMobile: Boolean) {
        prefs.edit()
            .putString(packageName, "${if (blockOnWifi) 1 else 0}:${if (blockOnMobile) 1 else 0}")
            .apply()
    }

    fun getRule(packageName: String): Pair<Boolean, Boolean> {
        val raw = prefs.getString(packageName, null) ?: return Pair(false, false)
        val parts = raw.split(":")
        val wifi = parts.getOrNull(0) == "1"
        val mobile = parts.getOrNull(1) == "1"
        return Pair(wifi, mobile)
    }

    /** Retourne l'ensemble des noms de paquets qui ont au moins une règle active. */
    fun getAllRules(): Map<String, Pair<Boolean, Boolean>> {
        return prefs.all.mapNotNull { (key, value) ->
            if (value is String) {
                val parts = value.split(":")
                key to Pair(parts.getOrNull(0) == "1", parts.getOrNull(1) == "1")
            } else null
        }.toMap()
    }

    fun isFirewallEnabled(): Boolean = prefs.getBoolean(KEY_FIREWALL_ENABLED, false)

    fun setFirewallEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FIREWALL_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "mamy_dataguard_rules"
        private const val KEY_FIREWALL_ENABLED = "__firewall_enabled__"
    }
}
