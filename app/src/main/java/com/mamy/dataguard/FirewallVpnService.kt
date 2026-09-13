package com.mamy.dataguard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FirewallVpnService
 *
 * Crée un tunnel VPN 100% local (aucune donnée ne sort vers un serveur externe).
 * Toutes les applications marquées comme "à bloquer" pour le type de réseau actif
 * (Wi-Fi ou Data Mobile) sont routées à travers ce tunnel. Comme le service ne
 * réémet jamais leurs paquets vers l'interface réseau réelle, leur trafic est
 * simplement abandonné (blackhole) : c'est le blocage.
 *
 * Les applications non listées ne passent jamais par le tunnel (comportement par
 * défaut de VpnService.Builder.addAllowedApplication : dès qu'on l'utilise, seules
 * les apps ajoutées sont incluses, toutes les autres bypassent automatiquement).
 */
class FirewallVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val notifiedUids = ConcurrentHashMap<Int, Boolean>()

    private lateinit var prefsManager: PrefsManager
    private lateinit var connectivityManager: ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopFirewall()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NotificationHelper.SERVICE_NOTIF_ID, NotificationHelper.buildServiceNotification(this))
                startFirewall()
                return START_STICKY
            }
        }
    }

    private fun startFirewall() {
        val wasAlreadyRunning = running.getAndSet(true)
        if (!wasAlreadyRunning) {
            prefsManager.setFirewallEnabled(true)
            registerNetworkCallback()
        }
        // Toujours reconstruire le tunnel : c'est ce qui applique les nouvelles
        // règles quand une app est cochée/décochée pendant que le service tourne déjà.
        rebuildTunnel()
    }

    private fun stopFirewall() {
        running.set(false)
        prefsManager.setFirewallEnabled(false)
        unregisterNetworkCallback()
        closeTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Suivi des réseaux physiques disponibles (Wi-Fi / Data Mobile), indépendamment
     *  du "réseau actif" au sens Android — qui devient notre propre tunnel VPN dès
     *  qu'il est établi, ce qui rendrait toute détection basée sur activeNetwork fausse. */
    private val availableNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                availableNetworks[network] = caps
                rebuildTunnel()
            }

            override fun onLost(network: Network) {
                availableNetworks.remove(network)
                rebuildTunnel()
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback as ConnectivityManager.NetworkCallback)

        // Pré-remplir avec l'état réseau déjà connu, pour que le tout premier
        // rebuildTunnel() (appelé juste après) dispose déjà des bonnes infos,
        // sans attendre le premier callback asynchrone.
        connectivityManager.allNetworks.forEach { net ->
            val caps = connectivityManager.getNetworkCapabilities(net)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                availableNetworks[net] = caps
            }
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        availableNetworks.clear()
    }

    private fun isOnWifi(): Boolean =
        availableNetworks.values.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }

    private fun isOnMobile(): Boolean =
        availableNetworks.values.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }

    /**
     * Recalcule la liste des applications à bloquer pour le réseau actuel
     * et reconstruit le tunnel VPN en conséquence.
     */
    private fun rebuildTunnel() {
        if (!running.get()) return

        val wifi = isOnWifi()
        val mobile = isOnMobile()

        val rules = prefsManager.getAllRules()
        val blockedPackages = rules.filter { (_, rule) ->
            val (blockWifi, blockMobile) = rule
            (wifi && blockWifi) || (mobile && blockMobile)
        }.keys

        closeTunnel()

        if (blockedPackages.isEmpty()) {
            // Rien à bloquer sur ce réseau : on n'établit pas de tunnel,
            // tout le trafic circule normalement.
            return
        }

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUNNEL_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        var addedAtLeastOne = false
        for (pkg in blockedPackages) {
            try {
                builder.addAllowedApplication(pkg)
                addedAtLeastOne = true
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Package introuvable, ignoré: $pkg")
            }
        }

        if (!addedAtLeastOne) return

        tunInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'établir le tunnel VPN", e)
            null
        }

        tunInterface?.let { startPacketReadLoop(it) }
    }

    /**
     * Boucle de lecture des paquets IP entrant dans le tunnel. Chaque paquet lu
     * est identifié (UID de l'app source) puis simplement abandonné : c'est le
     * blocage effectif. On en profite pour notifier l'utilisateur au premier
     * paquet détecté par app, par session.
     */
    private fun startPacketReadLoop(pfd: ParcelFileDescriptor) {
        executor.execute {
            val input = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)

            while (running.get() && tunInterface == pfd) {
                try {
                    val length = input.read(buffer)
                    if (length <= 0) continue
                    handlePacket(buffer, length)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Lecture paquet interrompue: ${e.message}")
                    break
                }
            }
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int) {
        val info = IpPacketParser.parse(buffer, length) ?: return

        // getConnectionOwnerUid nécessite Android 10 (API 29) minimum.
        // Sur les versions antérieures, le trafic est bloqué quand même,
        // seule la notification "app identifiée" n'est pas disponible.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val uid = try {
            connectivityManager.getConnectionOwnerUid(
                info.protocol,
                InetSocketAddress(info.sourceAddress, info.sourcePort),
                InetSocketAddress(info.destAddress, info.destPort)
            )
        } catch (e: Exception) {
            -1
        }

        if (uid <= 0) return
        if (notifiedUids.putIfAbsent(uid, true) == null) {
            val label = appLabelForUid(uid)
            NotificationHelper.notifyBlockedAttempt(this, label)
        }
        // On ne réécrit jamais le paquet vers l'extérieur -> il est abandonné (bloqué).
    }

    private fun appLabelForUid(uid: Int): String {
        val pm = packageManager
        val packages = pm.getPackagesForUid(uid) ?: return "UID $uid"
        val pkg = packages.firstOrNull() ?: return "UID $uid"
        return try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun closeTunnel() {
        notifiedUids.clear()
        tunInterface?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        tunInterface = null
    }

    override fun onRevoke() {
        stopFirewall()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopFirewall()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MamyDataGuard"
        private const val TUNNEL_ADDRESS = "10.10.10.2"
        const val ACTION_CONNECT = "com.mamy.dataguard.CONNECT"
        const val ACTION_DISCONNECT = "com.mamy.dataguard.DISCONNECT"

        /** Appelé quand une règle change en direct pour forcer une reconstruction du tunnel. */
        fun requestRulesRefresh(context: android.content.Context) {
            val intent = Intent(context, FirewallVpnService::class.java).apply {
                action = ACTION_CONNECT
            }
            context.startService(intent)
        }
    }
}
