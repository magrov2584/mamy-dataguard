package com.mamy.dataguard

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mamy.dataguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var adapter: AppListAdapter
    private var allApps: List<AppInfo> = emptyList()

    private val vpnPrepareLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startFirewallService()
        } else {
            binding.switchFirewall.isChecked = false
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* no-op, l'app fonctionne même sans notifications */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        requestNotificationPermissionIfNeeded()

        adapter = AppListAdapter(mutableListOf()) { app ->
            prefsManager.setRule(app.packageName, app.blockOnWifi, app.blockOnMobile)
            if (prefsManager.isFirewallEnabled()) {
                FirewallVpnService.requestRulesRefresh(this)
            }
        }
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter

        binding.switchFirewall.isChecked = prefsManager.isFirewallEnabled()
        binding.switchFirewall.setOnCheckedChangeListener { _, checked ->
            if (checked) requestVpnPermission() else stopFirewallService()
        }

        binding.btnBatteryOptim.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        loadInstalledApps()
    }

    /** Filtre la liste affichée par nom ou nom de paquet, sans perdre les règles déjà cochées. */
    private fun filterApps(query: String) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
        adapter.updateList(filtered)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startFirewallService()
        }
    }

    private fun startFirewallService() {
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_CONNECT
        }
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private fun stopFirewallService() {
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.battery_already_ignored, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val appInfos = installedApps
            .filter { it.packageName != packageName } // ne pas s'auto-lister
            .map { appInfo ->
                val (blockWifi, blockMobile) = prefsManager.getRule(appInfo.packageName)
                AppInfo(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    uid = appInfo.uid,
                    icon = try { pm.getApplicationIcon(appInfo.packageName) } catch (e: Exception) { null },
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    blockOnWifi = blockWifi,
                    blockOnMobile = blockMobile
                )
            }
            .filter { !it.isSystemApp } // n'afficher que les applications installées par l'utilisateur
            .sortedBy { it.label.lowercase() }

        allApps = appInfos
        adapter.updateList(appInfos)
    }
}
