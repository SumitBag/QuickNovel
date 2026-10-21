package com.lagradost.quicknovel.providers

import app.cash.quickjs.QuickJs
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Universal bridge mapping LNReader JavaScript plugins to QuickNovel's MainAPI.
 */
open class LNReaderBridge(
    override val name: String,
    override val mainUrl: String,
    private val pluginJs: String,
    override val hasMainPage: Boolean = true,
    override val iconId: Int? = null
) : MainAPI() {

    private val mapper = jacksonObjectMapper()

    interface NetworkBridge {
        fun fetch(url: String): String
    }

    private fun executeInJs(block: (QuickJs) -> String): String {
        QuickJs.create().use { quickJs ->
            quickJs.set("networkBridge", NetworkBridge::class.java, object : NetworkBridge {
                override fun fetch(url: String): String {
                    return try {
                        runBlocking(Dispatchers.IO) {
                            val response = app.get(url)
                            response.text
                        }
                    } catch (e: Exception) {
                        ""
                    }
                }
            })

            val runtimeInit = """
                var global = globalThis;
                function fetch(url) {
                    var text = networkBridge.fetch(url);
                    return {
                        text: function() { return text; },
                        json: function() { return JSON.parse(text); }
                    };
                }
            """.trimIndent()

            quickJs.evaluate(runtimeInit)
            quickJs.evaluate(pluginJs)
            return block(quickJs)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? = withContext(Dispatchers.IO) {
        try {
            val jsonResult = executeInJs { js ->
                js.evaluate("JSON.stringify(searchNovels('$query'))") as String
            }
            val list: List<Map<String, Any?>> = mapper.readValue(jsonResult)
            list.mapNotNull { item ->
                val title = item["name"] as? String ?: return@mapNotNull null
                val url = item["url"] as? String ?: return@mapNotNull null
                val poster = item["cover"] as? String
                SearchResponse(
                    name = title,
                    url = fixUrl(url),
                    posterUrl = poster,
                    apiName = name
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? = withContext(Dispatchers.IO) {
        try {
            val jsonResult = executeInJs { js ->
                js.evaluate("JSON.stringify(parseNovelAndChapters('$url'))") as String
            }
            val data: Map<String, Any?> = mapper.readValue(jsonResult)
            val title = data["name"] as? String ?: return@withContext null
            val cover = data["cover"] as? String
            val author = data["author"] as? String
            val description = data["summary"] as? String
            val rawChapters = data["chapters"] as? List<Map<String, Any?>> ?: emptyList()

            val chapters = rawChapters.mapNotNull { ch ->
                val chName = ch["name"] as? String ?: return@mapNotNull null
                val chUrl = ch["url"] as? String ?: return@mapNotNull null
                val releaseDate = ch["releaseTime"] as? String
                ChapterData(chName, fixUrl(chUrl), releaseDate, null)
            }

            StreamResponse(
                url = fixUrl(url),
                name = title,
                data = chapters.toMutableList(),
                author = author,
                posterUrl = cover,
                synopsis = description,
                apiName = name
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadHtml(url: String): String? = withContext(Dispatchers.IO) {
        try {
            executeInJs { js ->
                js.evaluate("parseChapter('$url')") as? String ?: ""
            }.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
