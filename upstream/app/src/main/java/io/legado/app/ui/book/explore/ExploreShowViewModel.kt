package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.AggregateLoader
import io.legado.app.model.aggregation.AggregateLoadingPolicy
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.aggregation.LocalCategoryPolicy
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.model.multisource.SourceRequestGate
import io.legado.app.model.multisource.core.BookIdentity
import io.legado.app.model.multisource.core.IdentityMatcher
import io.legado.app.utils.ChineseUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap


@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    val booksData = MutableLiveData<List<SearchBook>>()
    val errorLiveData = MutableLiveData<String>()
    private var sourceUrl: String? = null
    private var exploreUrl: String? = null
    private var page = 1
    private var books = linkedSetOf<SearchBook>()
    private var initialized = false
    var isAggregate = false
        private set
    var aggregateCategory: String? = null
        private set
    private var aggregateJob: Job? = null
    private var catalogueJob: Job? = null
    private var catalogueUpdatesJob: Job? = null
    @Volatile private var catalogueRunning = false
    @Volatile private var pendingCatalogueUpdate = false
    @Volatile private var aggregateGeneration = 0
    @Volatile private var loading = false
    val loadingLiveData = MutableLiveData<Boolean>()
    val aggregateHasMore = MutableLiveData<Boolean>()

    /** One independent state per explicit refresh; a cancelled old request cannot mutate the new list. */
    private class AggregateState {
        val books = linkedMapOf<String, MutableList<SearchBook>>()
        val pages = ConcurrentHashMap<String, Int>()
        val cursor = AggregateLoader.Cursor()
        val exhausted = ConcurrentHashMap.newKeySet<String>()
        @Volatile var fingerprints: Map<String, String> = emptyMap()

        fun initialCoverage(): Int {
            val limit = AggregateLoadingPolicy.SOURCES_PER_ROUND
            if (pages.size >= limit || exhausted.size >= limit) return limit
            return (pages.keys + exhausted).toSet().size.coerceAtMost(limit)
        }
        @Volatile var published: List<SearchBook> = emptyList()
        var failures = 0
    }
    @Volatile private var aggregateState = AggregateState()

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                    }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun initData(intent: Intent) {
        if (initialized) return
        initialized = true
        sourceUrl = intent.getStringExtra("sourceUrl")
        exploreUrl = intent.getStringExtra("exploreUrl")
        aggregateCategory = intent.getStringExtra("aggregateCategory")
        isAggregate = sourceUrl == AggregationPolicy.ALL_SOURCES
        explore()
    }

    fun explore() {
        if (isAggregate) {
            ensureCatalogueJobs()
            exploreAggregate()
            return
        }
        val url = exploreUrl ?: return
        val origin = sourceUrl ?: return
        if (loading) return
        loading = true
        loadingLiveData.postValue(true)
        val generation = aggregateGeneration
        aggregateJob = viewModelScope.launch(IO) {
            try {
                val source = appDb.bookSourceDao.getBookSource(origin)
                    ?: error(context.getString(io.legado.app.R.string.aggregation_empty))
                val result = withTimeoutOrNull(AggregateLoadingPolicy.SINGLE_SOURCE_TIMEOUT_MILLIS) {
                    SourceRequestGate.request(origin) { WebBook.exploreBookAwait(source, resolveMenuUrl(source, url, aggregateCategory), page) }
                } ?: error(context.getString(io.legado.app.R.string.timeout))
                coroutineContext.ensureActive()
                if (generation != aggregateGeneration) return@launch
                books.addAll(result)
                appDb.searchBookDao.insert(*result.toTypedArray())
                if (generation != aggregateGeneration) return@launch
                booksData.postValue(books.toList())
                page++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error.printOnDebug()
                if (generation == aggregateGeneration) errorLiveData.postValue(error.localizedMessage ?: context.getString(io.legado.app.R.string.aggregation_empty))
            } finally {
                if (generation == aggregateGeneration) {
                    loading = false
                    loadingLiveData.postValue(false)
                }
            }
        }
    }

    /** Called only inside a visible, bounded foreground request; never by metadata scans. */
    private suspend fun resolveMenuUrl(source: BookSource, url: String, selected: String?): String {
        if (url != ExploreAggregation.DEFERRED_MENU) return url
        val category = selected?.let { if (it == AggregationPolicy.ALL_SOURCES) it else LocalCategoryPolicy.category(it) }
        val kinds = source.exploreKinds().filter { !it.title.startsWith("ERROR:") && !it.url.isNullOrBlank() }
        val matched = kinds.firstOrNull { category == null || category == AggregationPolicy.ALL_SOURCES ||
            LocalCategoryPolicy.category(it.title, source.bookSourceGroup) == category }
        val resolved = (matched ?: kinds.firstOrNull().takeIf { category == LocalCategoryPolicy.category(source.bookSourceName) })?.url
            ?: error("此來源沒有目前分類的可用入口，請在單源查看分類")
        check(resolved != ExploreAggregation.DEFERRED_MENU) { "本地分類入口尚未解析" }
        return resolved
    }

    fun refreshAggregate() {
        cancelLoading()
        aggregateState = AggregateState()
        ensureCatalogueJobs(refresh = true)
        exploreAggregate()
    }

    fun cancelLoading() {
        aggregateGeneration++
        catalogueUpdatesJob?.cancel()
        catalogueUpdatesJob = null
        catalogueJob?.cancel()
        catalogueJob = null
        catalogueRunning = false
        pendingCatalogueUpdate = false
        aggregateJob?.cancel()
        aggregateJob = null
        loading = false
        loadingLiveData.postValue(false)
    }

    fun resumeLoading() {
        if (!initialized) return
        if (!isAggregate) {
            if (!loading && booksData.value.isNullOrEmpty()) explore()
            return
        }
        ensureCatalogueJobs()
        // A larger metadata snapshot is an incremental update, not a user refresh. Keep
        // already displayed books, their page numbers and the reader's current focus.
        exploreAggregate(discoveryOnly = true)
    }

    private fun ensureCatalogueJobs(refresh: Boolean = false) {
        val generation = aggregateGeneration
        if (catalogueUpdatesJob?.isActive != true) {
            catalogueUpdatesJob = viewModelScope.launch {
                ExploreAggregation.catalogueUpdates.collect {
                    if (generation != aggregateGeneration) return@collect
                    pendingCatalogueUpdate = true
                    val settled = aggregateState.initialCoverage()
                    // Once the first page is visible, growing metadata is not a reason to
                    // rebuild every source/fingerprint on Main. Read the next snapshot on
                    // scroll or once a completed pass can prove a source changed/disappeared.
                    if (!loading && (aggregateState.published.isEmpty() ||
                        settled < AggregateLoadingPolicy.SOURCES_PER_ROUND || ExploreAggregation.catalogueProgress.complete)) {
                        exploreAggregate(discoveryOnly = true)
                    } else if (!ExploreAggregation.catalogueProgress.complete) aggregateHasMore.value = true
                }
            }
        }
        if (catalogueJob?.isActive != true) {
            catalogueRunning = true
            catalogueJob = viewModelScope.launch(IO) {
                try {
                    // Shared with Main's category collector: only the metadata owner scans;
                    // result requests below read snapshots and never wait on that scan lock.
                    ExploreAggregation.discoverCategories(context, refresh)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation == aggregateGeneration) {
                        AppLog.put("发现分类自动载入失败", error)
                    }
                } finally {
                    withContext(Main) {
                        if (generation == aggregateGeneration) {
                            catalogueRunning = false
                            publishLoadingState()
                        }
                    }
                }
            }
        }
    }

    private fun publishLoadingState() {
        val waitingForMetadata = aggregateState.published.isEmpty() && catalogueRunning &&
            !ExploreAggregation.catalogueProgress.complete
        loadingLiveData.postValue(loading || waitingForMetadata)
    }

    private fun exploreAggregate(discoveryOnly: Boolean = false) {
        if (loading) return
        loading = true
        pendingCatalogueUpdate = false
        loadingLiveData.postValue(true)
        val generation = aggregateGeneration
        val state = aggregateState
        fun current() = generation == aggregateGeneration && state === aggregateState
        suspend fun publishMore(more: Boolean) = withContext(Main) {
            if (current()) aggregateHasMore.value = more
        }
        aggregateJob = viewModelScope.launch(IO) {
            try {
                val requested = aggregateCategory ?: exploreUrl ?: AggregationPolicy.ALL_SOURCES
                val category = if (requested == AggregationPolicy.ALL_SOURCES) requested else LocalCategoryPolicy.category(requested)
                do {
                    coroutineContext.ensureActive()
                    // null/ALL is a real all-books view; this API is a lightweight committed
                    // snapshot, independent of slow or invalid sources still being classified.
                    val completeBefore = ExploreAggregation.catalogueProgress.complete
                    val routes = ExploreAggregation.snapshotRoutesForCategory(context, category)
                        .distinctBy { it.source.bookSourceUrl }
                    if (!current()) return@launch
                    val complete = completeBefore && ExploreAggregation.catalogueProgress.complete
                    val previous = state.fingerprints
                    val versions = routes.associate { it.source.bookSourceUrl to it.fingerprint }
                    val obsolete = previous.keys.filter { key ->
                        val version = versions[key]
                        if (version == null) complete else previous[key] != version
                    }
                    // A growing partial catalogue cannot prove a previously seen route vanished.
                    val next = if (complete || previous.isEmpty()) versions else previous + versions
                    withContext(Main) {
                        if (!current()) return@withContext
                        state.fingerprints = next
                        for (key in obsolete) {
                            state.books.remove(key)
                            state.pages.remove(key)
                            state.exhausted.remove(key)
                        }
                    }
                    if (!current()) return@launch
                    val rank = HashMap<String, Int>(routes.size)
                    routes.forEachIndexed { index, route -> rank[route.source.bookSourceUrl] = index }
                    if (obsolete.isNotEmpty()) publishBooks(state, rank, generation)
                    // Metadata growth may enable an empty list, but does not auto-turn pages
                    // once books are on screen. More pages remain driven by user scrolling.
                    val initialSettled = state.initialCoverage()
                    if (discoveryOnly && state.published.isNotEmpty() &&
                        initialSettled >= AggregateLoadingPolicy.SOURCES_PER_ROUND) {
                        publishMore(routes.any { it.source.bookSourceUrl !in state.exhausted } ||
                            !ExploreAggregation.catalogueProgress.complete)
                        return@launch
                    }
                    // Fill at most one initial source round as metadata arrives. This lets
                    // the first book appear immediately without leaving a one-source screen,
                    // while never requesting a successful source's second page automatically.
                    val initial = state.published.isEmpty() || discoveryOnly
                    fun eligible(index: Int): Boolean {
                        val key = routes[index].source.bookSourceUrl
                        return key !in state.exhausted && (!initial || !state.pages.containsKey(key))
                    }
                    if (routes.indices.none(::eligible)) {
                        withContext(Main) {
                            if (current() && state.published.isEmpty()) booksData.value = emptyList()
                        }
                        publishMore(!ExploreAggregation.catalogueProgress.complete ||
                            routes.any { it.source.bookSourceUrl !in state.exhausted })
                        break
                    }
                    val claimed = ConcurrentHashMap.newKeySet<String>()
                    val settled = ConcurrentHashMap.newKeySet<String>()
                    val limit = if (discoveryOnly && state.published.isNotEmpty())
                        (AggregateLoadingPolicy.SOURCES_PER_ROUND - initialSettled).coerceAtLeast(1)
                        else AggregateLoadingPolicy.SOURCES_PER_ROUND
                    val round = state.cursor.round(routes.size, limit, ::eligible)
                    AggregateLoader.load(routes,
                        timeoutMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS,
                        budgetMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS,
                        claims = AggregateLoader.Claims {
                            round.next()?.also { claimed.add(routes[it].source.bookSourceUrl) }
                        }, fetch = { route ->
                            val source = route.source.getBookSource()?.takeIf { it.enabled && it.enabledExplore }
                                ?: error("来源已停用或移除")
                            SourceRequestGate.request(route.source.bookSourceUrl) {
                                withTimeoutOrNull(AggregateLoadingPolicy.SOURCE_TIMEOUT_MILLIS) {
                                    val resolved = resolveMenuUrl(source, route.url, category)
                                    WebBook.exploreBookAwait(source, resolved,
                                        state.pages[route.source.bookSourceUrl] ?: 1)
                                }
                            }
                        }, onResult = { index, result ->
                            if (!current()) return@load
                            val key = routes[index].source.bookSourceUrl
                            settled.add(key)
                            if (result != null) appDb.searchBookDao.insert(*result.toTypedArray())
                            val committed = withContext(Main) {
                                if (!current()) return@withContext false
                                if (result == null) {
                                    state.failures++
                                    state.exhausted.add(key)
                                } else {
                                    val bucket = state.books.getOrPut(key) { mutableListOf() }
                                    val knownUrls = bucket.mapTo(hashSetOf()) { it.bookUrl }
                                    val fresh = result.filter { knownUrls.add(it.bookUrl) }
                                    if (fresh.isEmpty()) state.exhausted.add(key)
                                    state.pages[key] = (state.pages[key] ?: 1) + 1
                                    bucket.addAll(fresh)
                                }
                                true
                            }
                            if (committed && result != null) publishBooks(state, rank, generation)
                        })
                    coroutineContext.ensureActive()
                    if (!current()) return@launch
                    // A normal return can mean the round's deadline expired. Only claimed
                    // unfinished work is settled as timed out; cancellation skips this block.
                    val timedOut = claimed - settled
                    withContext(Main) {
                        if (!current()) return@withContext
                        state.exhausted.addAll(timedOut)
                        state.failures += timedOut.size
                    }
                    publishMore(routes.any { it.source.bookSourceUrl !in state.exhausted } ||
                        !ExploreAggregation.catalogueProgress.complete)
                    // An empty first batch automatically advances beyond a bad prefix. The
                    // loop is finite: every claimed source settles once, and never auto-pages.
                } while (current() && state.published.isEmpty())
                if (state.published.isEmpty() && ExploreAggregation.catalogueProgress.complete && state.failures > 0)
                    withContext(Main) {
                        if (current()) errorLiveData.value = context.getString(io.legado.app.R.string.aggregation_failures, state.failures)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Main) {
                    if (current()) errorLiveData.value = error.localizedMessage ?: "载入失败"
                }
            } finally {
                if (current()) withContext(Main) {
                    if (current()) {
                        loading = false
                        publishLoadingState()
                        if (pendingCatalogueUpdate) exploreAggregate(discoveryOnly = true)
                    }
                }
            }
        }
    }

    private suspend fun publishBooks(state: AggregateState, rank: Map<String, Int>, generation: Int) {
        // Snapshot mutable buckets on Main; merging remains IO work and cannot race refresh,
        // cancellation or a newly resumed request committing into this same state instance.
        val snapshot = withContext(Main) {
            if (generation != aggregateGeneration || state !== aggregateState) return@withContext null
            state.books.mapValues { it.value.toList() } to state.published
        } ?: return
        val lists = snapshot.first.entries.sortedBy { rank[it.key] ?: Int.MAX_VALUE }.map { it.value }
        val candidates = lists.flatten().groupBy(::bookKey)
        val previous = snapshot.second.associateBy(::bookKey)
        val merged = AggregationPolicy.fairMerge(lists, ::bookKey).map { candidate ->
            val key = bookKey(candidate)
            // Keep the representative URL if another source for the same work arrives later;
            // RecyclerView can update its source count without losing that book's focus.
            val representative = previous[key]?.let { old ->
                candidates[key]?.firstOrNull { it.bookUrl == old.bookUrl }
            } ?: candidate
            representative.copy().also { book -> candidates[key].orEmpty().forEach { book.addOrigin(it.origin) } }
        }
        withContext(Main) {
            if (generation == aggregateGeneration && state === aggregateState) {
                state.published = merged
                booksData.value = merged
            }
        }
    }

    private fun bookKey(book: SearchBook): String =
        IdentityMatcher.bookKey(BookIdentity(book.name, book.author), ChineseUtils::t2s)
            ?: "${book.origin}\u0000${book.bookUrl}"

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

}
