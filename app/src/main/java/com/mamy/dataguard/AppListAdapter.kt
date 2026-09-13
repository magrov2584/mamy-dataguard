package com.mamy.dataguard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
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

            // Évite que les listeners se déclenchent pendant le binding (recyclage de vues)
            switchWifi.setOnCheckedChangeListener(null)
            switchMobile.setOnCheckedChangeListener(null)
            switchWifi.isChecked = app.blockOnWifi
            switchMobile.isChecked = app.blockOnMobile

            switchWifi.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                app.blockOnWifi = checked
                onRuleChanged(app)
            }
            switchMobile.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                app.blockOnMobile = checked
                onRuleChanged(app)
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
