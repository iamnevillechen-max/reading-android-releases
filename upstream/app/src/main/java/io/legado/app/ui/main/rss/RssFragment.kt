package io.legado.app.ui.main.rss

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.model.cloud.CloudWebEntryPolicy
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.ui.aggregation.showSourceGroups
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.help.ReaderDiagnostics
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.FragmentRssBinding
import io.legado.app.databinding.ItemRssBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.RssCategories
import io.legado.app.model.aggregation.RssCategoryCatalogue
import io.legado.app.model.aggregation.AggregationDatabaseRevision
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.article.RssArticlesFragment
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.openUrl
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * 订阅界面
 */
class RssFragment() : VMBaseFragment<RssViewModel>(R.layout.fragment_rss),
    MainFragmentInterface,
    RssAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentRssBinding::bind)
    override val viewModel by viewModels<RssViewModel>()
    private val adapter by lazy {
        RssAdapter(requireContext(), this, this, viewLifecycleOwner.lifecycle)
    }
    private val searchView: SearchView get() = binding.titleBar.findViewById(R.id.search_view)
    private var groupDialogJob: Job? = null
    private var rssFlowJob: Job? = null
    private var aggregateMode = true
    private var categoryFlowJob: Job? = null
    private var categories: List<String> = emptyList()
    private var selectedCategory: String? = null
    private var categoryMetadataLoaded = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        aggregateMode = savedInstanceState?.getBoolean("aggregateMode", aggregateMode) ?: aggregateMode
        selectedCategory = (savedInstanceState?.getString("selectedCategory") ?: selectedCategory)?.let {
            if (it == RssCategories.UNCLASSIFIED) it else it.trim().takeIf(String::isNotEmpty)
        }
        setSupportToolbar(binding.titleBar.toolbar)
        initSearchView()
        initRecyclerView()
        binding.aggregationCategories.bind(viewLifecycleOwner.lifecycleScope, getString(R.string.aggregation_mode_all),
            selectedCategory ?: AggregationPolicy.ALL_SOURCES, getString(R.string.aggregation_uncategorized)) { key ->
            selectedCategory = key.takeUnless { it == AggregationPolicy.ALL_SOURCES }
            showCategoryArticles()
        }
        binding.aggregationNavigation.root.setOnCheckedChangeListener { _, checked ->
            if ((checked == R.id.mode_all) != aggregateMode) selectMode(checked == R.id.mode_all)
        }
        selectMode(aggregateMode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("aggregateMode", aggregateMode)
        outState.putString("selectedCategory", selectedCategory)
        super.onSaveInstanceState(outState)
    }

    private fun selectMode(all: Boolean) {
        val changed = aggregateMode != all
        aggregateMode = all
        binding.aggregationNavigation.root.check(if (all) R.id.mode_all else R.id.mode_single)
        if (changed) searchView.setQuery("", false)
        searchView.isGone = false
        searchView.queryHint = getString(if (all) R.string.aggregation_search_categories else R.string.rss)
        binding.aggregationCategories.isGone = !all
        binding.aggregateArticles.isVisible = all
        binding.recyclerView.isGone = all
        binding.tvEmptyMsg.isGone = true
        if (all) {
            showCategoryArticles()
            showCategories()
            initCategoryData()
        } else {
            categoryFlowJob?.cancel()
            childFragmentManager.commitNow {
                childFragmentManager.fragments.forEach { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) }
            }
        }
        if (all) rssFlowJob?.cancel() else upRssFlowJob(searchView.query.toString())
    }

    private fun initCategoryData() {
        categoryFlowJob?.cancel()
        if (!aggregateMode) return
        categoryFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    var lastRevision = -1L
                    RssCategoryCatalogue.snapshots.collect { state ->
                        categories = state.names
                        categoryMetadataLoaded = state.loaded && !state.pending
                        if (aggregateMode) {
                            showCategories()
                            if (state.loaded && lastRevision != state.revision) {
                                lastRevision = state.revision
                                (childFragmentManager.fragments.firstOrNull { child -> !child.isHidden } as? RssArticlesFragment)
                                    ?.resumeAggregate()
                            }
                        }
                    }
                }
                appDb.rssSourceDao.flowCategorySpecs().conflate()
                    .map { it to AggregationDatabaseRevision.rssSources() }
                    .distinctUntilChanged().flowOn(IO).catch { AppLog.put("訂閱分類資料更新失敗", it) }.collectLatest { (specs, _) ->
                        RssCategoryCatalogue.synchronize(specs)
                        // Structured lifecycle cancellation stops native menu work on leaving this page.
                        RssCategoryCatalogue.resolvePending()
                    }
            }
        }
    }

    private fun showCategories() {
        if (!aggregateMode) return
        binding.aggregationCategories.submit(categories, searchView.query.toString(), categoryMetadataLoaded)
    }

    private fun showCategoryArticles() {
        if (!aggregateMode || childFragmentManager.isStateSaved) return
        val tag = "rssCategory:${selectedCategory?.let { io.legado.app.utils.MD5Utils.md5Encode(it) } ?: "all"}"
        val existing = childFragmentManager.findFragmentByTag(tag)
        if (existing != null && !existing.isHidden) return
        val articles = existing ?: RssArticlesFragment(getString(R.string.aggregation_all_rss),
            AggregationPolicy.ALL_SOURCES, selectedCategory)
        val others = childFragmentManager.fragments.filter { it != articles }
        childFragmentManager.commitNow {
            setReorderingAllowed(true)
            others.forEach { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) }
            others.dropLast(1).forEach { remove(it) }
            if (existing == null) add(R.id.aggregate_articles, articles, tag)
            show(articles)
            // MainActivity preloads every tab: parent lifecycle plus this cap prevents
            // requests until the user actually opens this subscription category.
            setMaxLifecycle(articles, Lifecycle.State.RESUMED)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_rss, menu)
        menuInflater.inflate(R.menu.aggregation_rss, menu)
        menu.findItem(R.id.menu_group)?.isVisible = !CloudReader.isEnabled()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_aggregate_rss -> selectMode(true)
            R.id.menu_rss_config -> startActivity<RssSourceActivity>()
            R.id.menu_rss_star -> startActivity<RssFavoritesActivity>()
            R.id.menu_group -> {
                groupDialogJob?.cancel()
                groupDialogJob = showSourceGroups(appDb.rssSourceDao.flowEnabledGroups()) { group ->
                    selectMode(false)
                    searchView.setQuery("group:$group", true)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReaderDiagnostics.record("subscriptions_opened")
        // A category can disappear while FragmentManager has its state saved.
        if (aggregateMode) showCategoryArticles()
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.rss)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (aggregateMode) showCategories() else upRssFlowJob(newText)
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.adapter = adapter
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        adapter.addHeaderView {
            ItemRssBinding.inflate(layoutInflater, it, false).apply {
                tvName.setText(R.string.rule_subscription)
                ivIcon.setImageResource(R.drawable.image_legado)
                root.setOnClickListener {
                    startActivity<RuleSubActivity>()
                }
            }
        }
    }

    private fun upRssFlowJob(searchKey: String? = null) {
        rssFlowJob?.cancel()
        if (CloudReader.isEnabled()) {
            rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val sources = kotlinx.coroutines.withContext(IO) {
                        val state = CloudCatalogue.refresh("rss")
                        (state.routes.map { it.sourceId } + state.webEntries.map { it.sourceId })
                            .mapNotNull { appDb.rssSourceDao.getByKey(it) }.distinctBy { it.sourceUrl }
                            .filter { searchKey.isNullOrBlank() || it.sourceName.contains(searchKey, true) }
                    }
                    if (!aggregateMode) adapter.setItems(sources, adapter.diffCallback)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Existing sources remain usable while offline. */ }
            }
            return
        }
        if (aggregateMode) return
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.rssSourceDao.flowEnabledByGroup(key)
                }

                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.flowOn(IO).collect {
                adapter.setItems(it, adapter.diffCallback)
            }
        }
    }

    override fun openRss(rssSource: RssSource) {
        if (rssSource.singleUrl) {
            viewModel.getSingleUrl(rssSource) { url ->
                if (url.startsWith("http", true)) {
                    startActivity<ReadRssActivity> {
                        putExtra("title", rssSource.sourceName)
                        if (CloudReader.isRemote(rssSource)) {
                            putExtra("origin", rssSource.sourceUrl)
                            putExtra(CloudWebEntryPolicy.EXTRA_URL, url)
                            putExtra(CloudWebEntryPolicy.EXTRA_CLOUD_WEBSITE, true)
                        } else putExtra("origin", url)
                    }
                } else {
                    context?.openUrl(url)
                }
            }
        } else {
            startActivity<RssSortActivity> {
                putExtra("url", rssSource.sourceUrl)
            }
        }
    }

    override fun toTop(rssSource: RssSource) {
        viewModel.topSource(rssSource)
    }

    override fun edit(rssSource: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", rssSource.sourceUrl)
        }
    }

    override fun del(rssSource: RssSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rssSource.sourceName)
            noButton()
            yesButton {
                viewModel.del(rssSource)
            }
        }
    }

    override fun disable(rssSource: RssSource) {
        viewModel.disable(rssSource)
    }
}
