package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.model.aggregation.ExploreRoute
import io.legado.app.ui.aggregation.AggregationListPositions
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 发现列表
 */
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    ExploreShowAdapter.CallBack {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val adapter by lazy { ExploreShowAdapter(this, this) }
    private val loadMoreView by lazy { LoadMoreView(this) }
    private var sourceChooserJob: Job? = null
    private var pendingViewport: AggregationListPositions.Anchor? = null
    private val viewportRoute get() = AggregationListPositions.route("explore",
        intent.getStringExtra("sourceUrl"), intent.getStringExtra("exploreUrl"))
    private val aggregateCategory: String?
        get() = intent.getStringExtra("aggregateCategory")
    private val aggregateDiff = object : DiffUtil.ItemCallback<SearchBook>() {
        override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook) = oldItem.bookUrl == newItem.bookUrl
        override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook) =
            oldItem.name == newItem.name && oldItem.author == newItem.author &&
                oldItem.coverUrl == newItem.coverUrl && oldItem.intro == newItem.intro &&
                oldItem.latestChapterTitle == newItem.latestChapterTitle && oldItem.origins == newItem.origins
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pendingViewport = AggregationListPositions.from(savedInstanceState?.getBundle("listViewport"))
            ?: AggregationListPositions.recalled(viewportRoute)
        binding.titleBar.title = intent.getStringExtra("exploreName")
        fun restoreMode() = binding.aggregationNavigation.root.check(
            if (intent.getStringExtra("sourceUrl") == AggregationPolicy.ALL_SOURCES) R.id.mode_all else R.id.mode_single)
        restoreMode()
        binding.aggregationNavigation.modeAll.setOnClickListener {
            restoreMode()
            if (intent.getStringExtra("sourceUrl") != AggregationPolicy.ALL_SOURCES) {
                showRouteChooser(chooseSource = false)
            }
        }
        binding.aggregationNavigation.modeSingle.setOnClickListener {
            restoreMode()
            showSourceChooser()
        }
        initRecyclerView()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.loadingLiveData.observe(this) {
            if (it) loadMoreView.startLoad() else loadMoreView.stopLoad()
            if (!it) onExploreListChanged()
        }
        viewModel.aggregateHasMore.observe(this) {
            if (!it) loadMoreView.noMore() else loadMoreView.stopLoad()
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.aggregation_results, menu)
        menu.findItem(R.id.menu_aggregate_refresh).isVisible =
            intent.getStringExtra("sourceUrl") == AggregationPolicy.ALL_SOURCES
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_aggregate_refresh -> viewModel.refreshAggregate()
            R.id.menu_aggregate_sources -> showSourceChooser()
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun showSourceChooser() {
        showRouteChooser(chooseSource = true)
    }

    private fun showRouteChooser(chooseSource: Boolean) {
        if (sourceChooserJob?.isActive == true) return
        val progress = AlertDialog.Builder(this).setMessage(R.string.aggregation_loading_categories)
            .setNegativeButton(android.R.string.cancel) { _, _ -> sourceChooserJob?.cancel() }
            .create()
        progress.setOnCancelListener { sourceChooserJob?.cancel() }
        progress.show()
        sourceChooserJob = lifecycleScope.launch {
            try {
                val category = aggregateCategory ?: withContext(IO) {
                    val source = intent.getStringExtra("sourceUrl")
                    val url = intent.getStringExtra("exploreUrl")
                    if (source == null || url == null) null else
                        ExploreAggregation.categoryForRoute(this@ExploreShowActivity, source, url)
                }
                if (category != null) {
                    if (!chooseSource) openAll(category)
                    else {
                        val routes = withContext(IO) {
                            ExploreAggregation.snapshotRoutesForCategory(this@ExploreShowActivity, category)
                        }
                        progress.dismiss()
                        showSources(category, routes)
                    }
                } else {
                    val categories = withContext(IO) { ExploreAggregation.categoryLabels(this@ExploreShowActivity) }
                    progress.dismiss()
                    if (categories.isEmpty()) {
                        AlertDialog.Builder(this@ExploreShowActivity).setMessage(R.string.aggregation_empty)
                            .setPositiveButton(android.R.string.ok, null).show()
                    } else {
                        val keys = listOf(AggregationPolicy.ALL_SOURCES) + categories
                        AlertDialog.Builder(this@ExploreShowActivity).setTitle(R.string.aggregation_choose_category)
                            .setItems(keys.map(::categoryLabel).toTypedArray()) { _, index ->
                                val selected = keys[index]
                                if (!chooseSource) openAll(selected)
                                else sourceChooserJob = lifecycleScope.launch {
                                    val routes = withContext(IO) {
                                        ExploreAggregation.snapshotRoutesForCategory(this@ExploreShowActivity, selected)
                                    }
                                    if (!isFinishing) showSources(selected, routes)
                                }
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun showSources(category: String, routes: List<ExploreRoute>) {
        val labels = listOf(getString(R.string.aggregation_all_explore)) + routes.map { it.source.bookSourceName }
        AlertDialog.Builder(this).setTitle(R.string.aggregation_choose_sources)
                    .setItems(labels.toTypedArray()) { _, index ->
                        val route = routes.getOrNull(index - 1)
                        openRoute(category, route?.source?.bookSourceUrl ?: AggregationPolicy.ALL_SOURCES,
                            route?.url ?: category, "${labels[index]} · ${categoryLabel(category)}")
                    }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun categoryLabel(category: String) = if (category == AggregationPolicy.ALL_SOURCES)
        getString(R.string.aggregation_mode_all) else category

    private fun openAll(category: String) = openRoute(category, AggregationPolicy.ALL_SOURCES, category,
        if (category == AggregationPolicy.ALL_SOURCES) getString(R.string.aggregation_all_explore)
        else "${getString(R.string.aggregation_all_explore)} · $category")

    private fun openRoute(category: String, source: String, url: String, title: String) {
        rememberViewport()
        // Finish first: a singleTop activity must not reuse the instance being closed.
        finish()
        startActivity<ExploreShowActivity> {
            putExtra("aggregateCategory", category)
            putExtra("sourceUrl", source)
            putExtra("exploreUrl", url)
            putExtra("exploreName", title)
        }
    }

    private fun rememberViewport(): AggregationListPositions.Anchor? {
        val anchor = if (adapter.getActualItemCount() == 0) pendingViewport else
            AggregationListPositions.capture(binding.recyclerView) { adapter.getItemByLayoutPosition(it)?.bookUrl }
        AggregationListPositions.remember(viewportRoute, anchor)
        return anchor
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberViewport()?.let { outState.putBundle("listViewport", it.bundle()) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        rememberViewport()
        super.onPause()
    }

    override fun onExploreListChanged() {
        binding.recyclerView.post {
            val anchor = pendingViewport ?: return@post
            val keys = adapter.getItems().map { it.bookUrl }
            val complete = !viewModel.isAggregate || (viewModel.loadingLiveData.value == false &&
                keys == viewModel.booksData.value.orEmpty().map { it.bookUrl })
            if (AggregationListPositions.restore(binding.recyclerView, anchor, keys, complete)) pendingViewport = null
        }
    }

    override fun onStop() {
        super.onStop()
        sourceChooserJob?.cancel()
        viewModel.cancelLoading()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeLoading()
    }

    private fun initRecyclerView() {
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.recyclerView.applyNavigationBarPadding()
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) pendingViewport = null
            }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // A short list can reach the bottom during layout without user scrolling.
                if (dy > 0 && adapter.getActualItemCount() > 0 &&
                    !recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                }
            }
        })
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun upData(books: List<SearchBook>) {
        if (viewModel.isAggregate) {
            adapter.setItems(books, aggregateDiff)
            return
        }
        loadMoreView.stopLoad()
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            adapter.setItems(books)
        }
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }
}
