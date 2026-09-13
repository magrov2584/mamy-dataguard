package com.mamy.dataguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefsManager = PrefsManager(context)
        if (!prefsManager.isFirewallEnabled()) return

        // Le VPN doit avoir déjà été autorisé une première fois par l'utilisateur
        // pour pouvoir redémarrer sans interaction (VpnService.prepare renverra null).
        if (VpnService.prepare(context) != null) return

        val serviceIntent = Intent(context, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
