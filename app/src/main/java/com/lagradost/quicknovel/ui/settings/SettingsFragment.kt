package com.lagradost.quicknovel.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream4.BaseComposeFragment
import com.lagradost.quicknovel.APIRepository.Companion.providersActive
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.LaunchedEffectSkipFirst
import com.lagradost.quicknovel.ui.PluginsDialogFragment
import com.lagradost.quicknovel.util.Apis.Companion.apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiSettings
import com.lagradost.quicknovel.util.SingleSelectionHelper.showMultiDialog
import com.lagradost.quicknovel.util.SubtitleHelper
import com.mihon.common.preference.AndroidPreferenceStore
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState

class SettingsFragment : BaseComposeFragment(), SearchableSettings by SettingScreen {
    @Composable
    override fun Content() {
        val store = AndroidPreferenceStore(LocalContext.current)
        val locale by store.getString(
            stringResource(R.string.locale_key),
            "en",
        ).collectAsState()
        val theme by store.getString(
            stringResource(R.string.theme_key),
            "AmoledLight",
        ).collectAsState()
        val color by store.getString(
            stringResource(R.string.primary_color_key),
            "Normal",
        ).collectAsState()

        LaunchedEffectSkipFirst(locale) {
            activity?.recreate()
        }
        LaunchedEffectSkipFirst(theme) {
            activity?.recreate()
        }
        LaunchedEffectSkipFirst(color) {
            activity?.recreate()
        }
        super.Content()
    }

    companion object {
        fun showSearchProviders(context: Context?) {
            if (context == null) return
            val activity = context as? AppCompatActivity

            // Prompt user with an option to open the Extensions installer directly
            val options = arrayOf("Manage Extensions (Install/Uninstall)", "Configure Active Search Providers")
            
            androidx.appcompat.app.AlertDialog.Builder(context, R.style.AlertDialogCustom)
                .setTitle("Providers & Extensions")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            activity?.supportFragmentManager?.let { fm ->
                                PluginsDialogFragment().show(fm, "plugins_dialog")
                            }
                        }
                        1 -> {
                            val apiNames = apis.map { it.name }
                            val displayNames = apis.map {
                                val flag = SubtitleHelper.getFlagFromIso(it.lang) ?: "🌐"
                                "$flag ${it.name}"
                            }
                            val active = context.getApiSettings()
                            showMultiDialog(
                                displayNames,
                                apiNames.mapIndexed { index, s -> index to active.contains(s) }
                                    .filter { it.second }.map { it.first }.toList(),
                                context.getString(R.string.search_providers),
                                {}) { list ->
                                val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
                                settingsManager.edit {
                                    putStringSet(
                                        context.getString(R.string.search_providers_list_key),
                                        list.map { apiNames[it] }.toSet()
                                    )
                                }
                                providersActive = context.getApiSettings()
                            }
                        }
                    }
                }
                .show()
        }
    }
}
