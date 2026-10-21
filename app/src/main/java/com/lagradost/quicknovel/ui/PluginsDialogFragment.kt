package com.lagradost.quicknovel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.DynamicPluginManager
import com.lagradost.quicknovel.util.PluginCatalogItem
import kotlinx.coroutines.launch

class PluginsDialogFragment : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        PluginsScreen(onDismiss = { dismiss() })
                    }
                }
            }
        }
    }

    @Composable
    fun PluginsScreen(onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val context = requireContext()
        var plugins by remember { mutableStateOf<List<PluginCatalogItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var installedNames by remember {
            mutableStateOf(Apis.apis.map { it.name }.toSet())
        }

        LaunchedEffect(Unit) {
            plugins = DynamicPluginManager.fetchAvailablePlugins()
            isLoading = false
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Extensions", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(plugins) { plugin ->
                        val isInstalled = installedNames.contains(plugin.name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${plugin.lang.uppercase()} • v${plugin.version}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        if (isInstalled) {
                                            DynamicPluginManager.uninstallPlugin(context, plugin.id)
                                        } else {
                                            DynamicPluginManager.installPlugin(context, plugin)
                                        }
                                        Apis.reloadApis(context)
                                        installedNames = Apis.apis.map { it.name }.toSet()
                                    }
                                },
                                colors = if (isInstalled) {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                } else {
                                    ButtonDefaults.buttonColors()
                                }
                            ) {
                                Text(if (isInstalled) "Uninstall" else "Install")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
