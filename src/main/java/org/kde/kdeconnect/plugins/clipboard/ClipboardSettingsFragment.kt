/*
 * kdeconnect+：剪贴板插件设置，在官方设置后面追加 Shizuku 相关选项。规则见 PLUS.md。
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import top.miceworld.kdeconnectplus.shizuku.ShizukuClipboard

class ClipboardSettingsFragment : PluginSettingsFragment() {
    private var statusPreference: Preference? = null
    private var accessNoticePreference: SwitchPreferenceCompat? = null
    private val stateListener: () -> Unit = { refresh() }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val context = preferenceManager.context
        // 这组设置全应用通用，存在默认 SharedPreferences 里，不跟随本插件的按设备设置。
        val appPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val category = PreferenceCategory(context).apply {
            title = getString(R.string.plus_shizuku_category)
            isIconSpaceReserved = false
        }
        preferenceScreen.addPreference(category)

        category.addPreference(SwitchPreferenceCompat(context).apply {
            isPersistent = false
            isIconSpaceReserved = false
            title = getString(R.string.plus_shizuku_enable)
            summary = getString(R.string.plus_shizuku_description)
            isChecked = ShizukuClipboard.isEnabled(context)
            setOnPreferenceChangeListener { _, newValue ->
                appPreferences.edit { putBoolean(ShizukuClipboard.PREF_ENABLED, newValue as Boolean) }
                ShizukuClipboard.onEnabledChanged()
                true
            }
        })

        statusPreference = Preference(context).apply {
            isPersistent = false
            isIconSpaceReserved = false
            title = getString(R.string.plus_shizuku_status)
            setOnPreferenceClickListener {
                ShizukuClipboard.requestPermission()
                true
            }
        }.also(category::addPreference)

        accessNoticePreference = SwitchPreferenceCompat(context).apply {
            isPersistent = false
            isIconSpaceReserved = false
            title = getString(R.string.plus_shizuku_access_notifications)
            summary = getString(R.string.plus_shizuku_access_notifications_summary)
            setOnPreferenceChangeListener { _, newValue ->
                ThreadHelper.execute {
                    ShizukuClipboard.setAccessNotificationsEnabled(newValue as Boolean)
                    loadAccessNotice()
                }
                false
            }
        }.also(category::addPreference)
    }

    override fun onStart() {
        super.onStart()
        ShizukuClipboard.addStateListener(stateListener)
        refresh()
    }

    override fun onStop() {
        ShizukuClipboard.removeStateListener(stateListener)
        super.onStop()
    }

    private fun refresh() {
        val status = statusPreference ?: return
        val state = ShizukuClipboard.state
        status.summary = if (state == ShizukuClipboard.State.ERROR) {
            getString(state.summary, ShizukuClipboard.errorStatus)
        } else {
            getString(state.summary)
        }
        status.isSelectable = state == ShizukuClipboard.State.PERMISSION_REQUIRED
        accessNoticePreference?.isEnabled = false
        if (ShizukuClipboard.isReady) ThreadHelper.execute(::loadAccessNotice)
    }

    private fun loadAccessNotice() {
        val value = ShizukuClipboard.getAccessNotificationsEnabled()
        activity?.runOnUiThread {
            accessNoticePreference?.apply {
                isEnabled = value >= 0
                isChecked = value == 1
            }
        }
    }

    companion object {
        fun newInstance(pluginKey: String, deviceId: String): ClipboardSettingsFragment {
            return ClipboardSettingsFragment().apply {
                setArguments(pluginKey, deviceId, R.xml.clipboardplugin_preferences)
            }
        }
    }
}
