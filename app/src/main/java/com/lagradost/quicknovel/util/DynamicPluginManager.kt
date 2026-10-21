package com.lagradost.quicknovel.util

import android.content.Context
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
    val id: String,
    val name: String,
    val site: String,
    val lang: String = "en",
    val version: String = "1.0.0",
    val url: String // Direct URL to the .js plugin file
)

object DynamicPluginManager {
    private val mapper = jacksonObjectMapper()
    
    // Default repository index pointing to the LNReader plugins list
    var repositoryUrl: String = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v2.json"

    private fun getPluginsDir(context: Context): File {
        val dir = File(context.filesDir, "dynamic_plugins")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Fetches the list of available plugins from the remote JSON repository.
     */
    suspend fun fetchAvailablePlugins(repoUrl: String = repositoryUrl): List<PluginCatalogItem> = withContext(Dispatchers.IO) {
        try {
            val response = app.get(repoUrl)
            mapper.readValue<List<PluginCatalogItem>>(response.text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Downloads and installs a plugin by saving its .js file to internal storage.
     */
    suspend fun installPlugin(context: Context, plugin: PluginCatalogItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = app.get(plugin.url)
            val file = File(getPluginsDir(context), "${plugin.id}.js")
            file.writeText(response.text)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Removes an installed plugin file.
     */
    fun uninstallPlugin(context: Context, pluginId: String): Boolean {
        val file = File(getPluginsDir(context), "$pluginId.js")
        return if (file.exists()) file.delete() else false
    }

    /**
     * Loads all installed .js plugins from disk into QuickNovel MainAPI instances.
     */
    fun loadInstalledPlugins(context: Context): List<MainAPI> {
        val dir = getPluginsDir(context)
        val files = dir.listFiles { f -> f.extension == "js" } ?: return emptyList()
        
        return files.mapNotNull { file ->
            try {
                val jsContent = file.readText()
                val pluginId = file.nameWithoutExtension
                // Reads basic name and URL if present or defaults to ID
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
