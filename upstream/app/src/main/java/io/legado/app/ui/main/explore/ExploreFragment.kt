package io.legado.app.ui.main.explore

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.help.ReaderDiagnostics
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.explore.ExploreResultsFragment
import io.legado.app.ui.aggregation.AggregationListPositions
import io.legado.app.ui.aggregation.showSourceGroups
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现界面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val scope get() = viewLifecycleOwner.lifecycleScope

    public override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
    private val searchView: SearchView get() = binding.titleBar.findViewById(R.id.search_view)
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private var exploreFlowJob: Job? = null
    private var groupDialogJob: Job? = null
    private var aggregateMode = true
    private var categoryFlowJob: Job? = null
    private var categoryLoading = false
    private var categoryFilterEmpty = false
    private var categoryFailed = false
    private var categoryMetadataLoaded = false
    private var selectedCategory: String? = AggregationPolicy.ALL_SOURCES
    private var sourceViewport: AggregationListPositions.Anchor? = null
    private var sourceCloudMode = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        aggregateMode = savedInstanceState?.getBoolean("aggregateMode", aggregateMode) ?: aggregateMode
        selectedCategory = (savedInstanceState?.getString("selectedCategory") ?: selectedCategory)?.let {
            if (it == AggregationPolicy.ALL_SOURCES) it else io.legado.app.model.aggregation.LocalCategoryPolicy.category(it)
        }
        sourceViewport = AggregationListPositions.from(savedInstanceState?.getBundle("sourceViewport")) ?: sourceViewport
        setSupportToolbar(binding.titleBar.toolbar)
        initSearchView()
        initRecyclerView()
        binding.aggregationCategories.bind(viewLifecycleOwner.lifecycleScope, getString(R.string.aggregation_all_explore),
            selectedCategory ?: AggregationPolicy.ALL_SOURCES, onDisplayed = { query, matches ->
                categoryFilterEmpty = query.isNotEmpty() && matches == 0
                showCategoryStatus()
            }) { key ->
            selectedCategory = key
            showCategoryResults()
        }
        binding.aggregationNavigation.root.setOnCheckedChangeListener { _, checked ->
            if ((checked == R.id.mode_all) != aggregateMode) selectMode(checked == R.id.mode_all)
        }
        selectMode(aggregateMode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("aggregateMode", aggregateMode)
        outState.putString("selectedCategory", selectedCategory)
        if (!aggregateMode) rememberSourceViewport()
        sourceViewport?.let { outState.putBundle("sourceViewport", it.bundle()) }
        super.onSaveInstanceState(outState)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main_explore, menu)
        menuInflater.inflate(R.menu.aggregation_explore, menu)
        menu.findItem(R.id.menu_group)?.isVisible = !io.legado.app.model.cloud.CloudReader.isEnabled()
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
        adapter.cancelKindLoads()
    }

    override fun onResume() {
        super.onResume()
        ReaderDiagnostics.record("discovery_opened")
        // A category can disappear while FragmentManager has its state saved.
        if (aggregateMode) showCategoryResults()
        if (sourceCloudMode != io.legado.app.model.cloud.CloudReader.isEnabled()) {
            sourceCloudMode = io.legado.app.model.cloud.CloudReader.isEnabled()
            if (!aggregateMode) upExploreData(searchView.query.toString())
        }
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.screen_find)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (aggregateMode) showCategories() else upExploreData(newText)
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (sourceViewport != null) {
                    restoreSourceViewport()
                } else if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
            override fun onChanged() = restoreSourceViewport()
        })
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        if (aggregateMode) return
        if (io.legado.app.model.cloud.CloudReader.isEnabled()) {
            exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val parts = withContext(IO) {
                        val state = io.legado.app.model.cloud.CloudCatalogue.refresh("books")
                        state.routes.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it.sourceId) }
                            .distinctBy { it.bookSourceUrl }
                            .filter { searchKey.isNullOrBlank() || it.bookSourceName.contains(searchKey, true) }
                    }
                    if (!aggregateMode) {
                        binding.tvEmptyMsg.isGone = parts.isNotEmpty()
                        adapter.setItems(parts, diffItemCallBack)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { binding.tvEmptyMsg.isGone = false }
            }
            return
        }
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.bookSourceDao.flowExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupExplore(key)
                }

                else -> {
                    appDb.bookSourceDao.flowExplore(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || searchView.query.isNotEmpty()
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    private fun selectMode(all: Boolean) {
        val changed = aggregateMode != all
        if (changed && !aggregateMode) rememberSourceViewport()
        aggregateMode = all
        binding.aggregationNavigation.root.check(if (all) R.id.mode_all else R.id.mode_single)
        searchView.queryHint = getString(if (all) R.string.aggregation_search_categories else R.string.screen_find)
        if (changed) searchView.setQuery("", false)
        binding.rvFind.isGone = all
        binding.aggregationCategories.isGone = !all
        binding.aggregateResults.isVisible = all
        binding.tvAggregationStatus.isGone = !all
        if (all) {
            exploreFlowJob?.cancel()
            showCategoryResults()
            showCategories()
            observeCategories()
        } else {
            categoryFlowJob?.cancel()
            binding.tvEmptyMsg.setText(R.string.explore_empty)
            childFragmentManager.commitNow {
                childFragmentManager.fragments.forEach { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) }
            }
            upExploreData(searchView.query.toString())
            restoreSourceViewport()
        }
    }

    private fun observeCategories(refresh: Boolean = false) {
        categoryFlowJob?.cancel()
        val context = requireContext().applicationContext
        categoryFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var refreshCache = refresh
                launch {
                    ExploreAggregation.catalogueUpdates.collect {
                        val complete = ExploreAggregation.catalogueProgress.complete
                        viewModel.aggregationCategories = ExploreAggregation.categoryLabels(context)
                        categoryMetadataLoaded = complete && ExploreAggregation.catalogueProgress.complete
                        if (view != null && aggregateMode) showCategories()
                    }
                }
                // Lifecycle cancellation stops this consumer; committed metadata remains
                // available for immediate book loading and for the next foreground visit.
                appDb.invalidationTracker.createFlow(AppDatabase.BOOK_SOURCE_TABLE_NAME)
                    .conflate().collect {
                        categoryLoading = true
                        categoryFailed = false
                        categoryMetadataLoaded = false
                        showCategories()
                        try {
                            ExploreAggregation.discoverCategories(context, refreshCache)
                            refreshCache = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            categoryFailed = true
                            AppLog.put("全部发现分类更新失败", error)
                        } finally {
                            categoryLoading = false
                            if (view != null && aggregateMode) showCategories()
                        }
                    }
            }
        }
    }

    private fun showCategories() {
        if (!aggregateMode || view == null) return
        binding.aggregationCategories.submit(viewModel.aggregationCategories, searchView.query.toString(), categoryMetadataLoaded)
        showCategoryStatus()
    }

    private fun showCategoryStatus() {
        if (!aggregateMode || view == null) return
        val status = when {
            categoryFailed -> getString(R.string.aggregation_categories_failed)
            categoryFilterEmpty -> getString(R.string.aggregation_category_no_match)
            categoryLoading -> getString(R.string.aggregation_loading_categories)
            else -> null
        }
        binding.tvAggregationStatus.text = status.orEmpty()
        binding.tvAggregationStatus.isGone = status == null
        // The result pane owns its empty/loading state; category scanning must not cover it.
        binding.tvEmptyMsg.isGone = true
    }

    private fun showCategoryResults() {
        if (!aggregateMode || childFragmentManager.isStateSaved) return
        val category = selectedCategory ?: AggregationPolicy.ALL_SOURCES
        binding.aggregateResults.isVisible = true
        val selectedTag = "exploreCategory:${io.legado.app.utils.MD5Utils.md5Encode(category)}"
        val existing = childFragmentManager.findFragmentByTag(selectedTag)
        if (existing != null && !existing.isHidden) return
        val selected = existing ?: ExploreResultsFragment.newInstance(
            AggregationPolicy.ALL_SOURCES, category, category,
            if (category == AggregationPolicy.ALL_SOURCES) getString(R.string.aggregation_all_explore)
            else "${getString(R.string.aggregation_all_explore)} · $category")
        val others = childFragmentManager.fragments.filter { it != selected }
        childFragmentManager.commitNow {
            setReorderingAllowed(true)
            others.forEach { hide(it); setMaxLifecycle(it, Lifecycle.State.STARTED) }
            others.dropLast(1).forEach { remove(it) }
            if (existing == null) add(R.id.aggregate_results, selected, selectedTag)
            show(selected)
            setMaxLifecycle(selected, Lifecycle.State.RESUMED)
        }
    }

    private fun rememberSourceViewport() {
        sourceViewport = AggregationListPositions.capture(binding.rvFind) {
            adapter.getItemByLayoutPosition(it)?.bookSourceUrl
        } ?: sourceViewport
    }

    private fun restoreSourceViewport() {
        if (aggregateMode) return
        binding.rvFind.post {
            val anchor = sourceViewport ?: return@post
            val keys = adapter.getItems().map { it.bookSourceUrl }
            if (AggregationListPositions.restore(binding.rvFind, anchor, keys, true)) sourceViewport = null
        }
    }

    private fun openAggregateCategory(category: String) {
        startActivity<ExploreShowActivity> {
            putExtra("sourceUrl", AggregationPolicy.ALL_SOURCES)
            putExtra("exploreUrl", category)
            putExtra("aggregateCategory", category)
            putExtra("exploreName", "${getString(R.string.aggregation_all_explore)} · $category")
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        if (item.itemId == R.id.menu_aggregate_explore) {
            selectMode(true)
            return
        }
        if (item.itemId == R.id.menu_aggregate_refresh) {
            selectMode(true)
            (childFragmentManager.fragments.firstOrNull { !it.isHidden } as? ExploreResultsFragment)?.refresh()
            observeCategories(refresh = true)
            return
        }
        if (item.itemId == R.id.menu_group) {
            groupDialogJob?.cancel()
            groupDialogJob = showSourceGroups(appDb.bookSourceDao.flowExploreGroups()) { group ->
                selectMode(false)
                searchView.setQuery("group:$group", true)
            }
        }
    }

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
            putExtra("aggregateCategory", ExploreAggregation.categoryFor(requireContext(), sourceUrl, title))
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        startActivity<SearchActivity> {
            putExtra("searchScope", SearchScope(bookSource).toString())
        }
    }

    fun compressExplore() {
        if (aggregateMode) {
            childFragmentManager.fragments.firstOrNull { !it.isHidden }?.view
                ?.findViewById<RecyclerView>(R.id.recycler_view)?.smoothScrollToPosition(0)
        } else if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }

}
