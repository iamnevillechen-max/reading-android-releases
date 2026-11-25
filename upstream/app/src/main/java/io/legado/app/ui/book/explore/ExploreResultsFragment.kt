package io.legado.app.ui.book.explore

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.FragmentExploreResultsBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.ui.aggregation.AggregationListPositions
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.startActivity

/** Inline results beneath the parent's horizontal categories; source rules stay in the original VM. */
class ExploreResultsFragment : BaseFragment(R.layout.fragment_explore_results), ExploreShowAdapter.CallBack {
    private val viewModel by viewModels<ExploreShowViewModel>()
    private var binding: FragmentExploreResultsBinding? = null
    private var adapter: ExploreShowAdapter? = null
    private var footer: LoadMoreView? = null
    private var pendingViewport: AggregationListPositions.Anchor? = null
    private val viewportRoute get() = AggregationListPositions.route("explore",
        arguments?.getString("sourceUrl"), arguments?.getString("exploreUrl"))
    private val resumed get() = viewLifecycleOwnerLiveData.value?.lifecycle?.currentState
        ?.isAtLeast(Lifecycle.State.RESUMED) == true
    private val bookDiff = object : DiffUtil.ItemCallback<SearchBook>() {
        override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook) = oldItem.bookUrl == newItem.bookUrl
        override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook) =
            oldItem.name == newItem.name && oldItem.author == newItem.author &&
                oldItem.coverUrl == newItem.coverUrl && oldItem.intro == newItem.intro &&
                oldItem.latestChapterTitle == newItem.latestChapterTitle && oldItem.origins == newItem.origins
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bound = FragmentExploreResultsBinding.bind(view)
        binding = bound
        pendingViewport = AggregationListPositions.from(savedInstanceState?.getBundle("listViewport"))
            ?: pendingViewport ?: AggregationListPositions.recalled(viewportRoute)
        val results = ExploreShowAdapter(requireContext(), this)
        val loadMore = LoadMoreView(requireContext())
        adapter = results
        footer = loadMore
        results.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        bound.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        bound.recyclerView.adapter = results
        results.addFooterView { ViewLoadMoreBinding.bind(loadMore) }
        // Main preloads this page before discovery is opened. Start the animation only
        // when the VM reports an actual request, so that an idle hidden page stays idle.
        loadMore.stopLoad()
        loadMore.setOnClickListener { loadNext(force = true) }
        bound.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // Adapter/layout changes also emit onScrolled(0, 0). Only advancing
                // through visible books requests another page; the VM owns empty recovery.
                if (dy > 0 && results.getActualItemCount() > 0 &&
                    !recyclerView.canScrollVertically(1)) loadNext()
            }
        })
        viewModel.booksData.observe(viewLifecycleOwner) { books ->
            if (binding === bound) showBooks(books)
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) { error ->
            if (binding === bound) loadMore.error(error)
        }
        viewModel.loadingLiveData.observe(viewLifecycleOwner) { loading ->
            if (binding !== bound) return@observe
            // initData uses the VM's own executor. Also cancel an initial load that starts late,
            // after the parent has already hidden this child at STARTED.
            if (loading && !resumed) {
                viewModel.cancelLoading()
                loadMore.stopLoad()
            } else if (loading) loadMore.startLoad() else loadMore.stopLoad()
            restoreViewport()
        }
        viewModel.aggregateHasMore.observe(viewLifecycleOwner) { more ->
            if (binding === bound) {
                if (more) loadMore.stopLoad() else loadMore.noMore()
                restoreViewport()
            }
        }
        viewModel.upAdapterLiveData.observe(viewLifecycleOwner) { key ->
            if (binding === bound) results.notifyItemRangeChanged(0, results.getActualItemCount(), bundleOf(key to null))
        }
    }

    override fun onResume() {
        super.onResume()
        val bound = binding ?: return
        // Fragment.onResume runs just before the view lifecycle reaches RESUMED.
        // Defer the first request so a STARTED preloaded child never starts background work.
        bound.recyclerView.post {
            if (binding !== bound || !resumed) return@post
            val route = arguments
            if (route?.getString("sourceUrl").isNullOrBlank() || route?.getString("exploreUrl").isNullOrBlank()) {
                footer?.error(getString(R.string.aggregation_empty))
                return@post
            }
            viewModel.initData(Intent().putExtras(route!!))
            viewModel.resumeLoading()
            restoreViewport()
        }
    }

    override fun onPause() {
        pendingViewport = rememberViewport()
        viewModel.cancelLoading()
        footer?.stopLoad()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberViewport()?.let { outState.putBundle("listViewport", it.bundle()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        rememberViewport()
        viewModel.cancelLoading()
        binding?.recyclerView?.adapter = null
        adapter = null
        footer = null
        binding = null
        super.onDestroyView()
    }

    fun resumeAggregate() {
        if (binding != null && resumed) viewModel.resumeLoading()
    }

    /** Same operation as the original aggregate result page's refresh action. */
    fun refresh() {
        if (binding == null || !resumed || !viewModel.isAggregate) return
        footer?.hasMore()
        viewModel.refreshAggregate()
    }

    private fun loadNext(force: Boolean = false) {
        if (binding == null || !resumed) return
        val loadMore = footer ?: return
        if ((loadMore.hasMore && !loadMore.isLoading) || force && !loadMore.isLoading) {
            loadMore.hasMore()
            viewModel.explore()
        }
    }

    private fun showBooks(books: List<SearchBook>) {
        val results = adapter ?: return
        val loadMore = footer ?: return
        if (viewModel.isAggregate) {
            results.setItems(books, bookDiff)
            return
        }
        loadMore.stopLoad()
        if (books.isEmpty() && results.isEmpty()) loadMore.noMore(getString(R.string.empty))
        else if (results.getActualItemCount() == books.size) loadMore.noMore()
        else results.setItems(books, bookDiff)
    }

    private fun rememberViewport(): AggregationListPositions.Anchor? {
        val bound = binding ?: return pendingViewport
        val results = adapter ?: return pendingViewport
        val anchor = pendingViewport ?: if (results.getActualItemCount() == 0) null else
            AggregationListPositions.capture(bound.recyclerView) { results.getItemByLayoutPosition(it)?.bookUrl }
        AggregationListPositions.remember(viewportRoute, anchor)
        return anchor
    }

    private fun restoreViewport() {
        val bound = binding ?: return
        if (!resumed) return
        bound.recyclerView.post {
            if (binding !== bound || !resumed) return@post
            val anchor = pendingViewport ?: return@post
            val results = adapter ?: return@post
            val complete = !viewModel.isAggregate || viewModel.loadingLiveData.value == false
            if (AggregationListPositions.restore(bound.recyclerView, anchor,
                    results.getItems().map { it.bookUrl }, complete)) pendingViewport = null
        }
    }

    override fun onExploreListChanged() = restoreViewport()
    override fun isInBookshelf(book: SearchBook) = viewModel.isInBookShelf(book)
    override fun showBookInfo(book: SearchBook) {
        if (!resumed) return
        rememberViewport()
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    companion object {
        fun newInstance(sourceUrl: String, exploreUrl: String, aggregateCategory: String? = null,
            exploreName: String? = null) = ExploreResultsFragment().apply {
            arguments = bundleOf("sourceUrl" to sourceUrl, "exploreUrl" to exploreUrl,
                "aggregateCategory" to aggregateCategory, "exploreName" to exploreName)
        }
    }
}
