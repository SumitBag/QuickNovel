package com.lagradost.quicknovel.util

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.providers.LNReaderBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginCatalogItem(
    val id: String = "",
    val name: String = "",
    val site: String = "",
    val lang: String = "en",
    val version: String = "1.0.0",
    val url: String = ""
)

object DynamicPluginManager {
    private val mapper = jacksonObjectMapper()
    private const val TAG = "DynamicPluginManager"

    // Official LNReader v3.0.0 repository endpoints
    const val REPO_BASE = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0"
    var repositoryUrl: String = "$REPO_BASE/.dist/plugins.min.json"

    private fun getPluginsDir(context: Context): File {
        val dir = File(context.filesDir, "dynamic_plugins")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Fetches the plugins catalog from the repository.
     */
    suspend fun fetchAvailablePlugins(repoUrl: String = repositoryUrl): List<PluginCatalogItem> = withContext(Dispatchers.IO) {
        try {
            val response = app.get(repoUrl)
            val jsonText = response.text

            // Parse generic JSON maps to handle variations in the LNReader schema
            val rawList: List<Map<String, Any?>> = mapper.readValue(jsonText)
            
            rawList.mapNotNull { map ->
                val id = map["id"] as? String ?: return@mapNotNull null
                val name = map["name"] as? String ?: id
                val site = map["site"] as? String ?: ""
                val lang = map["lang"] as? String ?: "en"
                val version = map["version"]?.toString() ?: "1.0.0"

                // Resolve the plugin download URL if relative or absolute
                var downloadUrl = (map["url"] as? String) ?: ""
                if (downloadUrl.isBlank() || downloadUrl.startsWith(".")) {
                    downloadUrl = "$REPO_BASE/.dist/plugins/$lang/$id.js"
                } else if (!downloadUrl.startsWith("http")) {
                    downloadUrl = "$REPO_BASE/$downloadUrl"
                }

                PluginCatalogItem(
                    id = id,
                    name = name,
                    site = site,
                    lang = lang,
                    version = version,
                    url = downloadUrl
                )
            }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch plugins catalog: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Downloads and installs a plugin script to internal storage.
     */
    suspend fun installPlugin(context: Context, plugin: PluginCatalogItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = app.get(plugin.url)
            if (response.text.isNotBlank()) {
                val file = File(getPluginsDir(context), "${plugin.id}.js")
                file.writeText(response.text)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download plugin ${plugin.id}: ${e.message}", e)
            false
        }
    }

    /**
     * Uninstalls a plugin by deleting its script.
     */
    fun uninstallPlugin(context: Context, pluginId: String): Boolean {
        val file = File(getPluginsDir(context), "$pluginId.js")
        return if (file.exists()) file.delete() else false
    }

    /**
     * Loads installed plugins from disk.
     */
    fun loadInstalledPlugins(context: Context): List<MainAPI> {
        val dir = getPluginsDir(context)
        val files = dir.listFiles { f -> f.extension == "js" } ?: return emptyList()

        return files.mapNotNull { file ->
            try {
                val jsContent = file.readText()
                val pluginId = file.nameWithoutExtension
                LNReaderBridge(
                    name = pluginId.replaceFirstChar { it.uppercase() },
                    mainUrl = "https://$pluginId",
                    pluginJs = jsContent
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
