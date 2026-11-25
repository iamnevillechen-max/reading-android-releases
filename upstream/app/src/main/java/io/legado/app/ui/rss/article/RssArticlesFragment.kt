package io.legado.app.ui.rss.article


import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.FragmentRssArticlesBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.databinding.ViewAggregationNoticeBinding
import androidx.core.view.isVisible
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.CloudWebEntryPolicy
import io.legado.app.ui.aggregation.AggregationListPositions
import io.legado.app.ui.main.rss.RssViewModel
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.openUrl
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class RssArticlesFragment() : VMBaseFragment<RssArticlesViewModel>(R.layout.fragment_rss_articles),
    BaseRssArticlesAdapter.CallBack {

    constructor(sortName: String, sortUrl: String, aggregateCategory: String? = null) : this() {
        arguments = Bundle().apply {
            putString("sortName", sortName)
            putString("sortUrl", sortUrl)
            putString("aggregateCategory", aggregateCategory)
        }
    }

    private val binding by viewBinding(FragmentRssArticlesBinding::bind)
    private val activityViewModel by activityViewModels<RssSortViewModel>()
    public override val viewModel by viewModels<RssArticlesViewModel>()
    private val sourceViewModel by viewModels<RssViewModel>()
    private val adapter: BaseRssArticlesAdapter<*> by lazy {
        when (activityViewModel.rssSource?.articleStyle) {
            1 -> RssArticlesAdapter1(requireContext(), this@RssArticlesFragment)
            2 -> RssArticlesAdapter2(requireContext(), this@RssArticlesFragment)
            else -> RssArticlesAdapter(requireContext(), this@RssArticlesFragment)
        }
    }
    private val loadMoreView: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private var articlesFlowJob: Job? = null
    private var aggregateNotice: ViewAggregationNoticeBinding? = null
    private var pendingViewport: AggregationListPositions.Anchor? = null
    private val viewportRoute get() = AggregationListPositions.route("rss",
        if (aggregateMode) AggregationPolicy.ALL_SOURCES else activityViewModel.url,
        if (aggregateMode) viewModel.aggregateCategory else "${viewModel.sortName}\u0000${viewModel.sortUrl}")
    private val aggregateDiff = object : DiffUtil.ItemCallback<RssArticle>() {
        override fun areItemsTheSame(oldItem: RssArticle, newItem: RssArticle) =
            oldItem.origin == newItem.origin && oldItem.link == newItem.link
        override fun areContentsTheSame(oldItem: RssArticle, newItem: RssArticle) =
            oldItem.title == newItem.title && oldItem.pubDate == newItem.pubDate &&
                oldItem.read == newItem.read && oldItem.image == newItem.image
    }
    override val isGridLayout: Boolean
        get() = activityViewModel.isGridLayout
    override val aggregateMode: Boolean
        get() = viewModel.sortUrl == AggregationPolicy.ALL_SOURCES

    override fun sourceLabel(origin: String): String? = viewModel.aggregateSourceLabel(origin)

    fun resumeAggregate() {
        if (aggregateMode && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            viewModel.resumeAggregate()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments)
        pendingViewport = AggregationListPositions.from(savedInstanceState?.getBundle("listViewport"))
            ?: AggregationListPositions.recalled(viewportRoute)
        initView()
        initData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberViewport()?.let { outState.putBundle("listViewport", it.bundle()) }
        super.onSaveInstanceState(outState)
    }

    private fun rememberViewport(): AggregationListPositions.Anchor? {
        val anchor = if (adapter.getActualItemCount() == 0) pendingViewport else
            AggregationListPositions.capture(binding.recyclerView) { position ->
                adapter.getItemByLayoutPosition(position)?.let {
                    AggregationListPositions.route("article", it.origin, it.link)
                }
            }
        AggregationListPositions.remember(viewportRoute, anchor)
        return anchor
    }

    override fun onArticlesChanged() {
        binding.recyclerView.post {
            val anchor = pendingViewport ?: return@post
            val keys = List<String?>(adapter.getHeaderCount()) { null } + adapter.getItems().map {
                AggregationListPositions.route("article", it.origin, it.link)
            }
            if (AggregationListPositions.restore(binding.recyclerView, anchor, keys, !viewModel.isLoading)) pendingViewport = null
        }
    }

    private fun initView() = binding.run {
        refreshLayout.setColorSchemeColors(accentColor)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.applyNavigationBarPadding()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        recyclerView.layoutManager = if (activityViewModel.isGridLayout) {
            recyclerView.setPadding(8, 0, 8, 0)
            GridLayoutManager(requireContext(), 2)
        } else {
            recyclerView.addItemDecoration(VerticalDivider(requireContext()))
            LinearLayoutManager(requireContext())
        }
        recyclerView.adapter = adapter
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        if (aggregateMode) adapter.addHeaderView {
            ViewAggregationNoticeBinding.inflate(layoutInflater, it, false).also { notice ->
                aggregateNotice = notice
                notice.root.text = viewModel.aggregateInformation.value
                notice.root.isVisible = !notice.root.text.isNullOrBlank()
                notice.root.isFocusable = true
                notice.root.setOnClickListener { showWebSources() }
            }
        }
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        refreshLayout.setOnRefreshListener {
            if (aggregateMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@setOnRefreshListener
            if (aggregateMode) viewModel.loadAggregate(refresh = true) else loadArticles()
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) pendingViewport = null
            }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && adapter.getActualItemCount() > 0 && !recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                }
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshLayout.isRefreshing = true
                loadArticles()
                this@launch.cancel()
            }
        }
    }

    private fun showWebSources() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val sources = viewModel.aggregateWebSources.value.orEmpty()
        if (sources.isEmpty()) return
        AlertDialog.Builder(requireContext()).setTitle(R.string.aggregation_choose_sources)
            .setItems(sources.map { it.sourceName }.toTypedArray()) { _, index ->
                val source = sources[index]
                sourceViewModel.getSingleUrl(source) { url ->
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@getSingleUrl
                    if (url.startsWith("http", true)) {
                        startActivity<ReadRssActivity> {
                            putExtra("title", source.sourceName)
                            putExtra("origin", if (CloudReader.isRemote(source)) source.sourceUrl else url)
                            if (CloudReader.isRemote(source)) {
                                putExtra(CloudWebEntryPolicy.EXTRA_CLOUD_WEBSITE, true)
                                putExtra(CloudWebEntryPolicy.EXTRA_URL, CloudWebEntryPolicy.requireUrl(url))
                            }
                        }
                    } else context?.openUrl(url)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun initData() {
        if (aggregateMode) {
            viewModel.aggregateArticles.observe(viewLifecycleOwner) {
                adapter.setItems(it, aggregateDiff)
                if (viewModel.loadFinallyLiveData.value == false) {
                    loadMoreView.noMore(if (it.isEmpty()) getString(R.string.empty) else null)
                }
            }
            viewModel.aggregateInformation.observe(viewLifecycleOwner) { information ->
                aggregateNotice?.root?.apply {
                    text = information
                    isVisible = !information.isNullOrBlank()
                }
            }
            return
        }
        val rssUrl = activityViewModel.url ?: return
        articlesFlowJob?.cancel()
        articlesFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssArticleDao.flowByOriginSort(rssUrl, viewModel.sortName).catch {
                AppLog.put("订阅文章界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    private fun loadArticles() {
        if (aggregateMode) {
            viewModel.ensureAggregate()
            return
        }
        activityViewModel.rssSource?.let {
            viewModel.loadArticles(it)
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if (aggregateMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (viewModel.isLoading) return
        if ((loadMoreView.hasMore && adapter.getActualItemCount() > 0) || forceLoad) {
            loadMoreView.hasMore()
            if (aggregateMode) {
                viewModel.loadAggregate()
                return
            }
            activityViewModel.rssSource?.let {
                viewModel.loadMore(it)
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.loadErrorLiveData.observe(viewLifecycleOwner) {
            loadMoreView.error(it)
        }
        viewModel.loadFinallyLiveData.observe(viewLifecycleOwner) { hasMore ->
            binding.refreshLayout.isRefreshing = false
            if (!hasMore) {
                val empty = if (aggregateMode) viewModel.aggregateArticles.value.isNullOrEmpty()
                    else adapter.getActualItemCount() == 0
                loadMoreView.noMore(if (empty) getString(R.string.empty) else null)
            } else {
                loadMoreView.hasMore()
                loadMoreView.stopLoad()
            }
        }
    }

    override fun onPause() {
        rememberViewport()
        super.onPause()
        if (aggregateMode) {
            viewModel.cancelAggregate()
            binding.refreshLayout.isRefreshing = false
            loadMoreView.stopLoad()
        }
    }

    override fun onResume() {
        super.onResume()
        if (aggregateMode) viewModel.resumeAggregate()
    }

    override fun readRss(rssArticle: RssArticle) {
        if (aggregateMode) viewModel.markAggregateRead(rssArticle)
        activityViewModel.read(rssArticle)
        startActivity<ReadRssActivity> {
            putExtra("title", rssArticle.title)
            putExtra("origin", rssArticle.origin)
            putExtra("link", rssArticle.link)
        }
    }
}
