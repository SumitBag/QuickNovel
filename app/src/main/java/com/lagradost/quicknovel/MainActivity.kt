package com.lagradost.quicknovel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.anggrayudi.storage.StorageFile
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.lagradost.quicknovel.APIRepository.Companion.providersActive
import com.lagradost.quicknovel.BookDownloader2.openQuickStream
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.BookDownloader2Helper.checkWrite
import com.lagradost.quicknovel.BookDownloader2Helper.createQuickStream
import com.lagradost.quicknovel.BookDownloader2Helper.requestRW
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.CommonActivity.updateLocale
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import com.lagradost.quicknovel.NotificationHelper.requestNotifications
import com.lagradost.quicknovel.databinding.ActivityMainBinding
import com.lagradost.quicknovel.databinding.BottomPreviewBinding
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.mvvm.observeNullable
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.network.CloudflareKiller
import com.lagradost.quicknovel.providers.RedditProvider
import com.lagradost.quicknovel.ui.PluginsDialogFragment
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.ui.result.ResultFragment
import com.lagradost.quicknovel.ui.result.ResultViewModel
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Apis.Companion.apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiSettings
import com.lagradost.quicknovel.util.Apis.Companion.printProviders
import com.lagradost.quicknovel.util.Coroutines
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.main
import com.lagradost.quicknovel.util.InAppUpdater.Companion.runAutoUpdate
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.SettingsHelper.getRating
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.dismissSafe
import com.lagradost.quicknovel.util.UIHelper.fixSystemBarsPadding
import com.lagradost.quicknovel.util.UIHelper.getResourceColor
import com.lagradost.quicknovel.util.UIHelper.html
import com.lagradost.quicknovel.util.UIHelper.popupMenu
import com.lagradost.quicknovel.util.UIHelper.setImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.reflect.KClass

class MainActivity : AppCompatActivity() {
    companion object {
        private var _mainActivity: WeakReference<MainActivity>? = null
        private var mainActivity
            get() = _mainActivity?.get()
            private set(value) {
                _mainActivity = WeakReference(value)
            }

        fun loadPreviewPage(searchResponse: SearchResponse) {
            mainActivity?.loadPopup(searchResponse.url, searchResponse.apiName)
        }

        fun loadPreviewPage(searchResponse: ImmutableSearchResponse) {
            if (searchResponse.id == null) {
                mainActivity?.loadPopup(searchResponse.url, searchResponse.apiName)
            } else {
                mainActivity?.loadPopup(searchResponse)
            }
        }

        fun loadPreviewPage(card: DownloadFragment.DownloadDataLoaded) {
            mainActivity?.loadPopup(card)
        }

        fun loadPreviewPage(cached: ResultCached) {
            mainActivity?.loadPopup(cached)
        }

        fun importEpub() {
            mainActivity?.openEpubPicker()
        }

        fun importEpubs() {
            mainActivity?.openEpubPickers()
        }

        var app = Requests(
            OkHttpClient()
                .newBuilder()
                .ignoreAllSSLErrors()
                .readTimeout(30L, TimeUnit.SECONDS)
                .build(),
            responseParser = object : ResponseParser {
                val mapper: ObjectMapper = jacksonObjectMapper().configure(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false
                )

                override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
                    return mapper.readValue(text, kClass.java)
                }

                override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
                    return try {
                        mapper.readValue(text, kClass.java)
                    } catch (e: Exception) {
                        null
                    }
                }

                override fun writeValueAsString(obj: Any): String {
                    return mapper.writeValueAsString(obj)
                }
            }
        ).apply {
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        }

        val appWithInterceptor by lazy {
            Requests(
                baseClient = app.baseClient.newBuilder()
                    .addInterceptor(CloudflareKiller())
                    .build(),
                responseParser = app.responseParser
            ).apply {
                defaultHeaders = app.defaultHeaders
            }
        }

        lateinit var navOptions: NavOptions

        fun loadResult(url: String, apiName: String, startAction: Int = 0) {
            (activity as? AppCompatActivity)?.loadResult(url, apiName, startAction)
        }

        fun Activity?.navigate(@IdRes navigation: Int, arguments: Bundle? = null) {
            try {
                if (this is FragmentActivity) {
                    val navHostFragment =
                        supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment?
                    navHostFragment?.navController?.navigate(navigation, arguments)
                }
            } catch (t: Throwable) {
                logError(t)
            }
        }

        fun FragmentActivity.loadResult(url: String, apiName: String, startAction: Int = 0) {
            runOnUiThread {
                this.navigate(
                    R.id.global_to_navigation_results,
                    ResultFragment.newInstance(url, apiName, startAction)
                )
            }
        }

        fun Activity?.loadSearchResult(card: SearchResponse, startAction: Int = 0) {
            (this as AppCompatActivity?)?.loadResult(card.url, card.apiName, startAction)
        }

        fun AppCompatActivity.loadResultFromUrl(url: String?): Boolean {
            if (url == null) return false
            for (api in apis) {
                if (url.contains(api.mainUrl)) {
                    loadResult(url, api.name)
                    return true
                }
            }

            val reddit = RedditProvider()
            RedditProvider.getName(url)?.let { name ->
                try {
                    Coroutines.main {
                        val uri = withContext(Dispatchers.IO) {
                            createQuickStream(
                                QuickStreamData(
                                    QuickStreamMetaData(
                                        "Not found",
                                        name,
                                        reddit.name,
                                    ),
                                    null,
                                    mutableListOf(ChapterData("Single Post", url, null, null))
                                )
                            )
                        }
                        openQuickStream(uri)
                    }
                } catch (e: Exception) {
                    logError(e)
                }
                return true
            }
            return false
        }
    }

    private fun NavDestination.matchDestination(@IdRes destId: Int): Boolean =
        hierarchy.any { it.id == destId }

    private fun onNavDestinationSelected(item: MenuItem, navController: NavController): Boolean {
        val builder = NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
            .setEnterAnim(R.anim.enter_anim)
            .setExitAnim(R.anim.exit_anim)
            .setPopEnterAnim(R.anim.pop_enter)
            .setPopExitAnim(R.anim.pop_exit)
        if (item.order and Menu.CATEGORY_SECONDARY == 0) {
            builder.setPopUpTo(
                navController.graph.findStartDestination().id,
                inclusive = false,
                saveState = true
            )
        }
        val options = builder.build()
        return try {
            navController.navigate(item.itemId, null, options)
            navController.currentDestination?.matchDestination(item.itemId) == true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private val viewModel: ResultViewModel by viewModels()

    private fun hidePreviewPopupDialog() {
        viewModel.clear()
        bottomPreviewPopup.dismissSafe(this)
    }

    fun loadPopup(
        result: ImmutableSearchResponse,
    ) {
        viewModel.initState(result)
    }

    fun loadPopup(
        resultCached: ResultCached,
    ) {
        viewModel.initState(resultCached)
    }

    fun loadPopup(card: DownloadFragment.DownloadDataLoaded) {
        viewModel.initState(card)
    }

    fun loadPopup(
        url: String,
        apiName: String,
    ) {
        viewModel.initState(apiName, url)
    }

    fun importUri(uri: Uri) = safe {
        val ctx: Context = this

        safe {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        val file = StorageFile.from(ctx, uri)
        val fileName = file?.name
        val mimeType = file?.mimeType
        println("Loaded epub file. Selected URI path: $uri - Name: $fileName with type $mimeType")

        ioSafe {
            try {
                if (mimeType == "application/pdf" || fileName?.endsWith(".pdf") == true) {
                    BookDownloader2.downloadPDFWorkThread(uri, ctx)
                } else {
                    BookDownloader2.downloadWorkThread(uri, ctx)
                }
            } catch (t: Throwable) {
                logError(t)
                showToast(t.message)
            }
        }
    }

    private val epubPathsPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { uri ->
                importUri(uri)
            }
        }

    private val epubPathPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            importUri(uri)
        }

    private fun openEpubPicker() {
        safe {
            epubPathPicker.launch(
                arrayOf(
                    "application/pdf",
                    "application/epub+zip",
                )
            )
        }
    }

    private fun openEpubPickers() {
        safe {
            epubPathsPicker.launch(
                arrayOf(
                    "application/pdf",
                    "application/epub+zip",
                )
            )
        }
    }

    var bottomPreviewBinding: BottomPreviewBinding? = null
    var bottomPreviewPopup: BottomSheetDialog? = null
    private fun showPreviewPopupDialog(): BottomPreviewBinding {
        val ret = (bottomPreviewBinding ?: run {
            val builder = BottomSheetDialog(this)
            val bottom = BottomPreviewBinding.inflate(layoutInflater, null, false)
            builder.setContentView(bottom.root)
            builder.setOnDismissListener {
                bottomPreviewBinding = null
                bottomPreviewPopup = null
                viewModel.clear()
            }
            builder.setCanceledOnTouchOutside(true)
            builder.show()
            bottomPreviewPopup = builder
            bottom
        })
        bottomPreviewBinding = ret
        return ret
    }

    override fun onResume() {
        super.onResume()
        activity = this
        mainActivity = this
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val extraText = try {
                intent.getStringExtra(Intent.EXTRA_TEXT)
            } catch (e: Exception) {
                null
            }
            val cd = intent.clipData
            val item = if (cd != null && cd.itemCount > 0) cd.getItemAt(0) else null
            val url = item?.text?.toString()

            if (item?.uri != null && loadResultFromUrl(item.uri?.toString())) {
                return
            }
            if (url != null && loadResultFromUrl(url)) {
                return
            }
            if (extraText != null && loadResultFromUrl(extraText)) {
                return
            }
        }
        val data: String? = intent.data?.toString()
        loadResultFromUrl(data)
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
        super.onNewIntent(intent)
    }

    private fun updateNavBar(destination: NavDestination) {
        val isNavVisible = listOf(
            R.id.navigation_mainpage,
            R.id.navigation_homepage,
            R.id.navigation_history,
            R.id.navigation_download,
            R.id.navigation_search,
            R.id.navigation_settings,
        ).contains(destination.id)

        binding?.apply {
            navView.isVisible = isNavVisible
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLocale()
    }

    var binding: ActivityMainBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        mainActivity = this

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        CommonActivity.loadThemes(this)
        CommonActivity.init(this)

        // Dynamically load installed LNReader JS plugins
        Apis.reloadApis(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding!!.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        navController.addOnDestinationChangedListener { _: NavController, navDestination: NavDestination, bundle: Bundle? ->
            updateNavBar(navDestination)
        }

        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        val rippleColor = ColorStateList.valueOf(getResourceColor(R.attr.colorPrimary, 0.1f))
        navView.itemRippleColor = rippleColor
        navView.itemActiveIndicatorColor = rippleColor

        fixSystemBarsPadding(
            navView, heightResId = R.dimen.nav_view_height,
            padTop = false,
            overlayCutout = false
        )

        navView.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.navigation_extensions) {
                PluginsDialogFragment().show(supportFragmentManager, "plugins_dialog")
                false
            } else {
                onNavDestinationSelected(
                    item,
                    navController = navController
                )
            }
        }

        navOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setEnterAnim(R.anim.nav_enter_anim)
            .setExitAnim(R.anim.nav_exit_anim)
            .setPopEnterAnim(R.anim.nav_pop_enter)
            .setPopExitAnim(R.anim.nav_pop_exit)
            .setPopUpTo(navController.graph.startDestinationId, false)
            .build()

        observe(viewModel.readState) {
            bottomPreviewBinding?.apply {
                bookmark.setIconResource(if (it == ReadType.NONE) R.drawable.ic_baseline_bookmark_border_24 else R.drawable.ic_baseline_bookmark_24)
                bookmark.setText(it.stringRes)
            }
        }

        observe(viewModel.downloadState) { progressState ->
            val hasDownload = progressState != null && progressState.progress > 0

            bottomPreviewBinding?.downloadDeleteTrashFromResult?.apply {
                isVisible = hasDownload
                isClickable = hasDownload
            }
        }

        observeNullable(viewModel.loadResponse) { resource ->
            if (resource == null) {
                bottomPreviewPopup.dismissSafe(this)
                return@observeNullable
            }
            when (resource) {
                is Resource.Failure -> {
                    showToast(this, R.string.error_loading_novel)
                    hidePreviewPopupDialog()
                }

                is Resource.Loading -> {
                    showPreviewPopupDialog().apply {
                        resultviewPreviewLoading.isVisible = true
                        resultviewPreviewResult.isVisible = false
                    }
                }

                is Resource.Success -> {
                    val d = resource.value
                    showPreviewPopupDialog().apply {
                        downloadDeleteTrashFromResult.setOnClickListener {
                            viewModel.deleteAlert()
                        }

                        bookmark.setOnClickListener { view ->
                            view.popupMenu(
                                ReadType.entries.map { it.prefValue to it.stringRes },
                                selectedItemId = viewModel.readState.value?.prefValue
                            ) {
                                viewModel.bookmark(itemId)
                            }
                        }

                        readMore.setOnClickListener {
                            if(!d.isImported) {
                                loadResult(d.url, viewModel.apiName)
                                hidePreviewPopupDialog()
                            }
                        }

                        readMore.isVisible =
                            viewModel.apiName != IMPORT_SOURCE && viewModel.apiName != IMPORT_SOURCE_PDF
                        bookmark.isVisible =
                            viewModel.apiName != IMPORT_SOURCE && viewModel.apiName != IMPORT_SOURCE_PDF

                        resultviewPreviewLoading.isVisible = false
                        resultviewPreviewResult.isVisible = true

                        resultviewPreviewPoster.apply {
                            setImage(d.downloadImage())
                            setOnClickListener {
                                if(!d.isImported) {
                                    loadResult(d.url, viewModel.apiName)
                                    hidePreviewPopupDialog()
                                }
                            }
                        }

                        resultviewPreviewTitle.text = d.name

                        resultviewPreviewMoreInfo.setOnClickListener {
                            if(!d.isImported) {
                                loadResult(d.url, viewModel.apiName)
                                hidePreviewPopupDialog()
                            }
                        }

                        resultviewPreviewDescription.text =
                            d.synopsis?.html() ?: getString(R.string.no_data)

                        resultviewPreviewDescription.setOnClickListener { view ->
                            view.context?.let { ctx ->
                                val builder: AlertDialog.Builder =
                                    AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                                builder.setMessage(d.synopsis.html())
                                    .setTitle(d.name)
                                    .show()
                            }
                        }

                        d.rating?.let { rating ->
                            resultviewPreviewMetaRating.text = getRating(rating)
                            resultviewPreviewMetaRating.isVisible = true
                        } ?: run {
                            resultviewPreviewMetaRating.isVisible = false
                        }

                        resultviewPreviewMetaStatus.apply {
                            val statusTxt = d.status?.resource?.let { getString(it) } ?: ""
                            resultviewPreviewMetaStatus.text = statusTxt
                            resultviewPreviewMetaStatus.isVisible = statusTxt.isNotBlank()
                        }

                        if (d is StreamResponse) {
                            resultviewPreviewMetaChapters.text =
                                "${d.data.size} ${getString(R.string.chapter_sort)}"
                            resultviewPreviewMetaChapters.isVisible = d.data.isNotEmpty()
                        } else {
                            resultviewPreviewMetaChapters.isVisible = false
                        }
                    }
                }
            }
        }

        val apiNames = getApiSettings()
        providersActive = apiNames
        val edit = settingsManager.edit()
        edit.putStringSet(getString(R.string.search_providers_list_key), providersActive)
        edit.apply()

        thread {
            val keys = getKeys(DOWNLOAD_FOLDER)
            for (k in keys) {
                getKey<DownloadFragment.DownloadData>(k)
            }
        }

        ioSafe {
            runAutoUpdate()
        }

        handleIntent(intent)

        if (!checkWrite()) {
            requestRW()
        }
        requestNotifications()

        printProviders()

        thread {
            test()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    window?.navigationBarColor = colorFromAttribute(R.attr.primaryGrayBackground)
                    updateLocale()

                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )
    }

    fun test() {
    }
}
