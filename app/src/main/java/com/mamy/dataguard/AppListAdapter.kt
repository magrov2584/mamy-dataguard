package com.mamy.dataguard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mamy.dataguard.databinding.ItemAppBinding

class AppListAdapter(
    private val apps: MutableList<AppInfo>,
    private val onRuleChanged: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    inner class AppViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        with(holder.binding) {
            appIcon.setImageDrawable(app.icon)
            appName.text = app.label
            appPackage.text = app.packageName

            bindWifiIcon(holder, app)
            bindMobileIcon(holder, app)

            // Écouteurs ré-assignés à chaque binding (et non ajoutés en plus) pour éviter
            // les doublons de listeners lors du recyclage des ViewHolder par le RecyclerView.
            iconWifi.setOnClickListener {
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                val currentApp = apps[currentPosition]
                currentApp.blockOnWifi = !currentApp.blockOnWifi
                bindWifiIcon(holder, currentApp)
                onRuleChanged(currentApp)
            }

            iconMobile.setOnClickListener {
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                val currentApp = apps[currentPosition]
                currentApp.blockOnMobile = !currentApp.blockOnMobile
                bindMobileIcon(holder, currentApp)
                onRuleChanged(currentApp)
            }
        }
    }

    private fun bindWifiIcon(holder: AppViewHolder, app: AppInfo) {
        with(holder.binding.iconWifi) {
            if (app.blockOnWifi) {
                setImageResource(R.drawable.ic_wifi_blocked)
                contentDescription = context.getString(R.string.wifi_blocked_desc)
            } else {
                setImageResource(R.drawable.ic_wifi_allowed)
                contentDescription = context.getString(R.string.wifi_allowed_desc)
            }
        }
    }

    private fun bindMobileIcon(holder: AppViewHolder, app: AppInfo) {
        with(holder.binding.iconMobile) {
            if (app.blockOnMobile) {
                setImageResource(R.drawable.ic_mobile_blocked)
                contentDescription = context.getString(R.string.mobile_blocked_desc)
            } else {
                setImageResource(R.drawable.ic_mobile_allowed)
                contentDescription = context.getString(R.string.mobile_allowed_desc)
            }
        }
    }

    override fun getItemCount(): Int = apps.size

    fun updateList(newApps: List<AppInfo>) {
        apps.clear()
        apps.addAll(newApps)
        notifyDataSetChanged()
    }
}
