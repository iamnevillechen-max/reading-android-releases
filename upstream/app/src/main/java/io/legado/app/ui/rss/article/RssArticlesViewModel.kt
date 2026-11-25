package io.legado.app.ui.rss.article

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.rss.Rss
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.utils.stackTraceStr
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.AggregateLoader
import io.legado.app.model.aggregation.AggregateLoadingPolicy
import io.legado.app.model.aggregation.RssAggregation
import io.legado.app.model.aggregation.RssCategories
import io.legado.app.model.aggregation.RssCategoryCatalogue
import io.legado.app.model.aggregation.AggregationDatabaseRevision
import io.legado.app.utils.GSON
import io.legado.app.model.multisource.SourceRequestGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap


class RssArticlesViewModel(application: Application) : BaseViewModel(application) {
    val loadFinallyLiveData = MutableLiveData<Boolean>()
    val loadErrorLiveData = MutableLiveData<String>()
    @Volatile var isLoading = true
    var order = System.currentTimeMillis()
    private var nextPageUrl: String? = null
    var sortName: String = ""
    var sortUrl: String = ""
    var aggregateCategory: String? = null
        private set
    var page = 1
    val aggregateArticles = MutableLiveData<List<RssArticle>>()
    val aggregateInformation = MutableLiveData<String?>()
    val aggregateWebSources = MutableLiveData<List<RssSource>>()
    private var aggregateJob: Job? = null
    private var resumeCheckJob: Job? = null
    @Volatile private var aggregateGeneration = 0
    private var aggregateInitialized = false
    private data class Route(val source: RssSource, val sort: String, val url: String,
        var next: String? = url, var page: Int = 1, @Volatile var exhausted: Boolean = false) {
        val key get() = "${source.sourceUrl}\u0000$sort\u0000$url"
    }
    private var aggregateRoutes = emptyList<Route>()
    private val aggregateBuckets = linkedMapOf<String, MutableList<RssArticle>>()
    private var aggregateSources = emptyList<RssSource>()
    private var metadataCursor = AggregateLoader.Cursor()
    private var articleCursor = AggregateLoader.Cursor()
    private val preparedSources = ConcurrentHashMap.newKeySet<String>()
    private val pendingSortCacheInvalidations = ConcurrentHashMap.newKeySet<String>()
    private var aggregateConfigFingerprint: String? = null
    private var aggregateCloudRevision: String? = null
    private var aggregateCategoryRevision = -1L
    private val aggregateReadLinks = ConcurrentHashMap.newKeySet<String>()
    fun aggregateSourceLabel(origin: String): String? = aggregateSources.firstOrNull { it.sourceUrl == origin }?.sourceName
    fun markAggregateRead(article: RssArticle) {
        aggregateReadLinks.add(article.link)
        aggregateArticles.value = aggregateArticles.value.orEmpty().map {
            if (it.link == article.link) it.copy(read = true) else it
        }
    }

    private fun mergedAggregate(): List<RssArticle> =
        RssAggregation.merge(aggregateRoutes.map { aggregateBuckets[it.key].orEmpty() }).map {
            if (it.link in aggregateReadLinks) it.copy(read = true) else it
        }

    fun ensureAggregate() {
        if (!aggregateInitialized) {
            aggregateInitialized = true
            loadAggregate(refresh = true, clearSortCache = false)
        }
    }

    fun resumeAggregate() {
        if (!aggregateInitialized) return
        if (CloudReader.isEnabled()) {
            // Restoring a visible list is not a request for its next page. Resume
            // only unfinished initial filling; real scrolling owns pagination.
            if (aggregateCloudRevision != CloudReader.configurationRevision(context) ||
                (aggregateJob?.isActive != true && aggregateBuckets.values.all { it.isEmpty() })) {
                loadCloudAggregate(refresh = false)
            }
            return
        }
        // The initial request is already establishing this snapshot. Avoid starting it twice.
        if (aggregateConfigFingerprint == null && aggregateJob?.isActive == true) return
        resumeCheckJob?.cancel()
        val checkingGeneration = aggregateGeneration
        resumeCheckJob = viewModelScope.launch(IO) {
            val fingerprint = AggregationDatabaseRevision.rssSources()
            if (checkingGeneration != aggregateGeneration) return@launch
            resumeCheckJob = null
            val menusChanged = aggregateCategory != null && aggregateCategoryRevision != RssCategoryCatalogue.snapshots.value.revision
            if (fingerprint != aggregateConfigFingerprint || menusChanged) {
                if (fingerprint == aggregateConfigFingerprint && aggregateJob?.isActive == true) return@launch
                loadAggregate(refresh = true, clearSortCache = false, preserveLoaded = true)
            } else if (aggregateJob?.isActive != true && aggregateArticles.value.isNullOrEmpty()) {
                loadAggregate(refresh = aggregateRoutes.isEmpty(), clearSortCache = false)
            }
        }
    }

    fun cancelAggregate() {
        aggregateGeneration++
        resumeCheckJob?.cancel()
        resumeCheckJob = null
        aggregateJob?.cancel()
        aggregateJob = null
        isLoading = false
    }

    fun loadAggregate(refresh: Boolean = false, clearSortCache: Boolean = refresh, preserveLoaded: Boolean = false) {
        if (CloudReader.isEnabled()) {
            loadCloudAggregate(refresh)
            return
        }
        if (refresh) cancelAggregate() else if (isLoading) return
        isLoading = true
        val generation = aggregateGeneration
        aggregateJob = viewModelScope.launch(IO) {
            try {
                if (refresh) {
                    val enabledSources = appDb.rssSourceDao.all.filter { it.enabled && !CloudReader.isRemote(it) }
                    val nextSources = enabledSources.filter { !it.singleUrl && RssCategoryCatalogue.includesSource(it, aggregateCategory) }
                    val byId = nextSources.associateBy { it.sourceUrl }
                    // Initial loading has no buckets to keep. On refresh compare one source
                    // at a time, so a large rule library is never copied into two JSON maps.
                    val retained = if (preserveLoaded) aggregateSources.mapNotNullTo(hashSetOf()) { previous ->
                        byId[previous.sourceUrl]?.takeIf { GSON.toJson(it) == GSON.toJson(previous) }?.sourceUrl
                    } else emptySet()
                    val records = appDb.rssReadRecordDao.getRecords().mapTo(hashSetOf()) { it.record }
                    val fingerprint = AggregationDatabaseRevision.rssSources()
                    val cached = RssAggregation.readCache(context, nextSources, aggregateCategory).map { it.copy(read = it.link in records) }
                    val webSources = enabledSources.filter { it.singleUrl && RssCategoryCatalogue.includesSource(it, aggregateCategory) }
                    coroutineContext.ensureActive()
                    val adopted = withContext(Main) {
                        if (generation != aggregateGeneration) return@withContext false
                        aggregateRoutes = aggregateRoutes.filter { it.source.sourceUrl in retained }
                            .map { it.copy(source = byId.getValue(it.source.sourceUrl)) }
                        aggregateBuckets.keys.retainAll(aggregateRoutes.mapTo(hashSetOf()) { it.key })
                        preparedSources.retainAll(retained)
                        pendingSortCacheInvalidations.retainAll(retained)
                        metadataCursor = AggregateLoader.Cursor()
                        articleCursor = AggregateLoader.Cursor()
                        aggregateReadLinks.clear(); aggregateReadLinks.addAll(records)
                        val invalidateSorts = clearSortCache || (aggregateConfigFingerprint != null && fingerprint != aggregateConfigFingerprint)
                        aggregateConfigFingerprint = fingerprint
                        aggregateCategoryRevision = RssCategoryCatalogue.snapshots.value.revision
                        aggregateSources = nextSources
                        if (invalidateSorts) pendingSortCacheInvalidations.addAll(aggregateSources.filter { it.sourceUrl !in preparedSources }.map { it.sourceUrl })
                        aggregateWebSources.value = webSources
                        aggregateInformation.value = if (webSources.isNotEmpty())
                            context.getString(io.legado.app.R.string.aggregation_web_entries, webSources.size) else null
                        true
                    }
                    if (!adopted) return@launch
                    val visible = if (aggregateRoutes.isNotEmpty()) mergedAggregate() else cached
                    withContext(Main) { if (generation == aggregateGeneration) aggregateArticles.value = visible }
                }
                var failures = 0
                val delivery = Mutex()
                // Metadata preparation and article work share four slots. A ready source may
                // deliver its first page while unrelated dynamic classifications are still slow.
                val work = Semaphore(4)
                suspend fun fetch(route: Route): Pair<List<RssArticle>, String?>? = SourceRequestGate.request(route.source.sourceUrl) {
                    work.withPermit {
                        withTimeoutOrNull(AggregateLoadingPolicy.SOURCE_TIMEOUT_MILLIS) {
                            Rss.getArticlesAwait(route.sort, route.next ?: route.url, route.source, route.page)
                        }
                    }
                }
                suspend fun deliver(route: Route, result: Pair<List<RssArticle>, String?>?) = delivery.withLock {
                    if (generation != aggregateGeneration) return@withLock
                    if (result == null) {
                        failures++
                        route.exhausted = true
                    } else {
                        val articles = result.first
                        articles.forEach { it.order = order-- }
                        appDb.rssArticleDao.insert(*articles.toTypedArray())
                        if (generation != aggregateGeneration) return@withLock
                        val bucket = aggregateBuckets.getOrPut(route.key) { mutableListOf() }
                        val knownLinks = bucket.mapTo(hashSetOf()) { it.link }
                        val fresh = articles.filter { knownLinks.add(it.link) }
                        route.next = result.second
                        route.page++
                        route.exhausted = fresh.isEmpty() || route.next.isNullOrBlank()
                        bucket.addAll(fresh)
                        aggregateArticles.postValue(mergedAggregate())
                    }
                }
                suspend fun fetchSafely(route: Route) = try { fetch(route) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }

                val previousRoutes = aggregateRoutes.toList()
                val pendingMetadata = aggregateSources.any { it.sourceUrl !in preparedSources }
                val existingArticles = !preserveLoaded && previousRoutes.any { !it.exhausted }
                // After the first batch, keep finding new sources and advancing existing feeds
                // together; hundreds of slow classifications cannot block the next article page.
                val metadataLimit = if (existingArticles) AggregateLoadingPolicy.SOURCES_PER_ROUND / 2
                    else AggregateLoadingPolicy.SOURCES_PER_ROUND
                val articleLimit = if (pendingMetadata) AggregateLoadingPolicy.SOURCES_PER_ROUND - metadataLimit
                    else AggregateLoadingPolicy.SOURCES_PER_ROUND
                withTimeoutOrNull(AggregateLoadingPolicy.ROUND_BUDGET_MILLIS) {
                    coroutineScope {
                        if (pendingMetadata) launch {
                            val claims = metadataCursor.round(aggregateSources.size, metadataLimit) {
                                aggregateSources[it].sourceUrl !in preparedSources
                            }
                            AggregateLoader.load(aggregateSources,
                                timeoutMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS,
                                budgetMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS,
                                claims = claims, fetch = { source ->
                                    work.withPermit {
                                        // Catalogue and article preparation share one source mutex/native sort cache.
                                        RssCategoryCatalogue.resolve(source, refresh = source.sourceUrl in pendingSortCacheInvalidations)
                                            .filter { RssCategories.includesSort(source, it.first, aggregateCategory) }
                                            .map { Route(source, it.first, it.second) }
                                    }
                                }, onResult = { index, routes ->
                                    delivery.withLock {
                                        if (generation == aggregateGeneration) {
                                            preparedSources.add(aggregateSources[index].sourceUrl)
                                            pendingSortCacheInvalidations.remove(aggregateSources[index].sourceUrl)
                                            if (routes == null) failures++ else aggregateRoutes = aggregateRoutes + routes
                                        }
                                    }
                                    if (generation == aggregateGeneration) routes?.firstOrNull()?.let { route ->
                                        // Start immediately before a metadata worker reclaims the slot.
                                        launch(start = CoroutineStart.UNDISPATCHED) { deliver(route, fetchSafely(route)) }
                                    }
                                })
                        }
                        if (existingArticles && articleLimit > 0) launch {
                            val claims = articleCursor.round(previousRoutes.size, articleLimit) { !previousRoutes[it].exhausted }
                            AggregateLoader.load(previousRoutes,
                                timeoutMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS,
                                budgetMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS,
                                claims = claims, fetch = { fetchSafely(it) },
                                onResult = { index, result -> deliver(previousRoutes[index], result) })
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (generation != aggregateGeneration) return@launch
                val merged = mergedAggregate()
                if (merged.isNotEmpty()) RssAggregation.writeCache(context, aggregateSources, merged, aggregateCategory) {
                    generation == aggregateGeneration
                }
                if (failures > 0) loadErrorLiveData.postValue(context.getString(io.legado.app.R.string.aggregation_failures, failures))
                loadFinallyLiveData.postValue(aggregateRoutes.any { !it.exhausted } ||
                    aggregateSources.any { it.sourceUrl !in preparedSources })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == aggregateGeneration) {
                    loadErrorLiveData.postValue(e.localizedMessage ?: "載入失敗")
                    loadFinallyLiveData.postValue(false)
                }
            } finally {
                if (generation == aggregateGeneration) {
                    isLoading = false; aggregateJob = null
                    if (aggregateCategory != null && aggregateCategoryRevision != RssCategoryCatalogue.snapshots.value.revision) resumeAggregate()
                }
            }
        }
    }

    /** The phone consumes approved server routes; it never evaluates local RSS source rules. */
    private fun loadCloudAggregate(refresh: Boolean) {
        val configuration = CloudReader.configurationRevision(context)
        val replaceLocalState = refresh || aggregateCloudRevision != configuration
        if (replaceLocalState) cancelAggregate() else if (aggregateJob?.isActive == true) return
        isLoading = true
        val generation = aggregateGeneration
        aggregateJob = viewModelScope.launch(IO) {
            try {
                val state = CloudCatalogue.refresh("rss", refresh)
                coroutineContext.ensureActive()
                if (generation != aggregateGeneration) return@launch
                if (replaceLocalState) {
                    aggregateBuckets.clear()
                    aggregateRoutes = emptyList()
                    articleCursor = AggregateLoader.Cursor()
                    aggregateCloudRevision = configuration
                }
                aggregateReadLinks.addAll(appDb.rssReadRecordDao.getRecords().map { it.record })
                val selected = state.routes.filter { aggregateCategory == null ||
                    it.categoryLabel == aggregateCategory || it.categoryId == aggregateCategory }
                val sources = selected.mapNotNull { appDb.rssSourceDao.getByKey(it.sourceId) }.distinctBy { it.sourceUrl }
                aggregateSources = sources
                val websites = state.webEntries.filter { aggregateCategory == null ||
                    it.categoryLabel == aggregateCategory || it.categoryId == aggregateCategory }
                    .mapNotNull { appDb.rssSourceDao.getByKey(it.sourceId) }.distinctBy { it.sourceUrl }
                aggregateWebSources.postValue(websites)
                aggregateInformation.postValue(null)
                val byId = sources.associateBy { it.sourceUrl }
                val previous = aggregateRoutes.associateBy { it.key }
                val active = selected.mapNotNull { route -> byId[route.sourceId]?.let { source ->
                    val value = Route(source, route.label, route.url)
                    previous[value.key] ?: value
                } }
                // Retain displayed articles from a source withdrawn since the last refresh.
                val activeKeys = active.mapTo(hashSetOf()) { it.key }
                aggregateRoutes = active + aggregateRoutes.filter { it.key !in activeKeys }.onEach { it.exhausted = true }
                if (aggregateBuckets.isEmpty()) {
                    val cached = RssAggregation.readCache(context, sources, aggregateCategory)
                    if (cached.isNotEmpty()) aggregateArticles.postValue(cached.map { it.copy(read = it.link in aggregateReadLinks) })
                }
                val delivery = Mutex()
                do {
                    val candidates = aggregateRoutes
                    val claimed = ConcurrentHashMap.newKeySet<Int>()
                    val settled = ConcurrentHashMap.newKeySet<Int>()
                    val cursorClaims = articleCursor.round(candidates.size, AggregateLoadingPolicy.SOURCES_PER_ROUND) { !candidates[it].exhausted }
                    val claims = AggregateLoader.Claims { cursorClaims.next()?.also { claimed.add(it) } }
                    AggregateLoader.load(candidates, timeoutMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS,
                        budgetMillis = AggregateLoadingPolicy.ROUND_BUDGET_MILLIS, claims = claims,
                        fetch = { route ->
                            SourceRequestGate.request(route.source.sourceUrl) {
                                CloudReader.rssArticles(route.source, route.sort, route.next ?: route.url, route.page)
                            }
                        }, onResult = { index, result -> delivery.withLock {
                            if (generation != aggregateGeneration) return@withLock
                            settled.add(index)
                            val route = candidates[index]
                            if (result == null) route.exhausted = true else {
                                val bucket = aggregateBuckets.getOrPut(route.key) { mutableListOf() }
                                val seen = bucket.mapTo(hashSetOf()) { it.link }
                                val articles = result.first.filter { seen.add(it.link) }
                                articles.forEach { it.order = order-- }
                                appDb.rssArticleDao.insert(*articles.toTypedArray())
                                bucket.addAll(articles)
                                route.next = result.second
                                route.page++
                                route.exhausted = articles.isEmpty() || result.second.isNullOrBlank()
                                aggregateArticles.postValue(mergedAggregate())
                            }
                        } })
                    coroutineContext.ensureActive()
                    if (generation != aggregateGeneration) return@launch
                    // The round budget can cancel a claimed request just before
                    // delivery. Settle those claims once on normal expiry, while
                    // external lifecycle cancellation above remains retryable.
                    (claimed - settled).forEach { candidates[it].exhausted = true }
                    // An empty first batch must continue to later ready feeds without a scroll.
                } while (aggregateBuckets.values.all { it.isEmpty() } && aggregateRoutes.any { !it.exhausted })
                val merged = mergedAggregate()
                if (merged.isNotEmpty()) RssAggregation.writeCache(context, sources, merged, aggregateCategory) { generation == aggregateGeneration }
                if (aggregateRoutes.isEmpty() && websites.isEmpty()) loadErrorLiveData.postValue("服务器暂无已核验的订阅源")
                loadFinallyLiveData.postValue(aggregateRoutes.any { !it.exhausted })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == aggregateGeneration) {
                    loadErrorLiveData.postValue(error.localizedMessage ?: "云端订阅加载失败")
                    loadFinallyLiveData.postValue(false)
                }
            } finally { if (generation == aggregateGeneration) isLoading = false }
        }
    }

    fun init(bundle: Bundle?) {
        bundle?.let {
            sortName = it.getString("sortName") ?: ""
            sortUrl = it.getString("sortUrl") ?: ""
            aggregateCategory = it.getString("aggregateCategory")
        }
    }

    fun loadArticles(rssSource: RssSource) {
        isLoading = true
        page = 1
        order = System.currentTimeMillis()
        Rss.getArticles(viewModelScope, sortName, sortUrl, rssSource, page).onSuccess(IO) {
            nextPageUrl = it.second
            val articles = it.first
            articles.forEach { rssArticle ->
                rssArticle.order = order--
            }
            appDb.rssArticleDao.insert(*articles.toTypedArray())
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                appDb.rssArticleDao.clearOld(rssSource.sourceUrl, sortName, order)
            }
            val hasMore = articles.isNotEmpty() && if (CloudReader.isRemote(rssSource)) {
                !nextPageUrl.isNullOrBlank()
            } else !rssSource.ruleNextPage.isNullOrEmpty()
            loadFinallyLiveData.postValue(hasMore)
            isLoading = false
        }.onError {
            isLoading = false
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    fun loadMore(rssSource: RssSource) {
        isLoading = true
        page++
        val pageUrl = nextPageUrl
        if (pageUrl.isNullOrEmpty()) {
            isLoading = false
            loadFinallyLiveData.postValue(false)
            return
        }
        Rss.getArticles(viewModelScope, sortName, pageUrl, rssSource, page).onSuccess(IO) {
            nextPageUrl = it.second
            val appended = loadMoreSuccess(it.first)
            if (CloudReader.isRemote(rssSource)) loadFinallyLiveData.postValue(appended && !nextPageUrl.isNullOrBlank())
            isLoading = false
        }.onError {
            isLoading = false
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    private fun loadMoreSuccess(articles: MutableList<RssArticle>): Boolean {
        if (articles.isEmpty()) {
            loadFinallyLiveData.postValue(false)
            return false
        }
        val firstArticle = articles.first()
        val dbFirstArticle = appDb.rssArticleDao.get(firstArticle.origin, firstArticle.link)
        val lastArticle = articles.last()
        val dbLastArticle = appDb.rssArticleDao.get(lastArticle.origin, lastArticle.link)
        if (dbFirstArticle != null && dbLastArticle != null) {
            loadFinallyLiveData.postValue(false)
            return false
        } else {
            articles.forEach {
                it.order = order--
            }
            appDb.rssArticleDao.append(*articles.toTypedArray())
            return true
        }
    }

}
