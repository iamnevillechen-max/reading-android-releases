package io.legado.app.cloud

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.update.reader.ReaderStartupUpdates
import io.legado.app.help.update.reader.ReaderUpdateManager
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.model.cloud.CloudReader
import io.legado.app.ui.aggregation.HorizontalCategoryView
import io.legado.app.ui.book.explore.ExploreResultsFragment
import io.legado.app.ui.book.explore.ExploreShowViewModel
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.main.rss.RssViewModel
import io.legado.app.ui.rss.article.RssArticlesFragment
import io.legado.app.ui.rss.article.RssArticlesViewModel
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Paginated remote metadata is exercised through the actual native discovery/RSS screens. */
@RunWith(AndroidJUnit4::class)
class CloudCatalogueIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(timeout:Long=10_000,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {
            var ready=false;main{ready=predicate()};if(ready)return
            SystemClock.sleep(20)
        }
        fail("Owned cloud UI did not reach its expected visible state")
    }
    private fun texts(view:View):List<String> {
        if(!view.isShown)return emptyList()
        val own=if(view is TextView)listOf(view.text.toString())else emptyList()
        return own+if(view is ViewGroup)(0 until view.childCount).flatMap{texts(view.getChildAt(it))}else emptyList()
    }
    private fun open(activity:MainActivity,id:Int)=main {
        val control=requireNotNull(activity.findViewById<View>(id))
        assertTrue(control.isShown);assertTrue(control.performClick())
    }
    private fun withMain(fixture:CloudIntegrationFixture,action:(MainActivity)->Unit) {
        val application=fixture.application
        val sets=listOf(application.defaultSharedPreferences,LocalConfig,
            application.getSharedPreferences("reader_update_v1",0),application.getSharedPreferences("aggregation_categories",0))
        val previous=sets.associateWith{it.all.toMap()}
        val cache=File(application.cacheDir,"aggregate-rss")
        val previousCache=cache.listFiles().orEmpty().mapTo(hashSetOf()){it.name}
        val loader=ReaderUpdateManager.manifestLoaderForTests
        check(!ReaderUpdateManager.checking())
        ReaderUpdateManager.manifestLoaderForTests={throw IOException("Owned cloud fixture does not query release metadata")}
        application.defaultSharedPreferences.edit().putString(PreferKey.defaultHomePage,"bookshelf")
            .putBoolean(PreferKey.showDiscovery,true).putBoolean(PreferKey.showRss,true)
            .putBoolean(PreferKey.autoRefresh,false).putBoolean(PreferKey.autoCheckNewBackup,false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk",true).putLong("appVersionCode",appInfo.versionCode)
            .putString("password","").putBoolean("appCrash",false).putLong("lastBackup",System.currentTimeMillis()).commit()
        val resumed=AtomicReference<MainActivity?>()
        val activities=linkedSetOf<MainActivity>()
        val lifecycle=ActivityLifecycleCallback{activity,stage->
            if(activity is MainActivity && stage==Stage.CREATED)activities.add(activity)
            if(activity is MainActivity && stage==Stage.RESUMED)resumed.set(activity)
        }
        main{ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)}
        try {
            main{application.startActivity(Intent(application,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
            await{resumed.get()?.let{!it.isFinishing && !it.isDestroyed}==true}
            action(requireNotNull(resumed.get()))
        } finally {
            val jobs=arrayListOf<Job>()
            main {
                activities.filterNot{it.isDestroyed}.forEach{activity->
                    activity.lifecycleScope.coroutineContext[Job]?.let(jobs::add)
                    ViewModelProvider(activity)[MainViewModel::class.java].viewModelScope.coroutineContext[Job]?.let(jobs::add)
                    activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().forEach{parent->
                        parent.viewLifecycleOwnerLiveData.value?.lifecycleScope?.coroutineContext?.get(Job)?.let(jobs::add)
                        parent.viewModel.viewModelScope.coroutineContext[Job]?.let(jobs::add)
                        parent.childFragmentManager.fragments.filterIsInstance<ExploreResultsFragment>().forEach{child->
                            ViewModelProvider(child)[ExploreShowViewModel::class.java].viewModelScope.coroutineContext[Job]?.let(jobs::add)
                        }
                    }
                    activity.supportFragmentManager.fragments.filterIsInstance<RssFragment>().forEach{parent->
                        parent.viewLifecycleOwnerLiveData.value?.lifecycleScope?.coroutineContext?.get(Job)?.let(jobs::add)
                        ViewModelProvider(parent)[RssViewModel::class.java].viewModelScope.coroutineContext[Job]?.let(jobs::add)
                        parent.childFragmentManager.fragments.filterIsInstance<RssArticlesFragment>().forEach{child->
                            ViewModelProvider(child)[RssArticlesViewModel::class.java].viewModelScope.coroutineContext[Job]?.let(jobs::add)
                        }
                    }
                    activity.finish()
                }
            }
            await{activities.all{it.isDestroyed}}
            runBlocking{withTimeout(10_000){jobs.distinct().joinAll()}}
            main{
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
                ReaderStartupUpdates.resetForTests()
            }
            ReaderUpdateManager.manifestLoaderForTests=loader
            previous.forEach{(preferences,values)->restoreCloudPreferences(preferences,values)}
            cache.listFiles().orEmpty().filter{it.isFile && it.name !in previousCache}.forEach{it.delete()}
        }
    }

    @Test(timeout=40_000)
    fun paginatedHealthySourcesAndMergedCategoriesReachMainAllCategoryAllWithoutLoadingLocalRules() {
        CloudIntegrationFixture(sameBook=false).use{fixture->
            val counts=arrayListOf<Int>()
            val state=runBlocking{CloudCatalogue.refresh("books",force=true){counts.add(it.routes.size)}}
            assertEquals(listOf(1,2),counts)
            assertEquals(listOf("urban","fantasy"),state.categories.map{it.id})
            assertEquals(2,state.routes.size);assertEquals(2,state.checked);assertEquals(2,state.total)
            assertEquals(2,appDb.bookSourceDao.allCount())
            fixture.sources.forEach{source->
                val actual=appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!
                assertTrue(CloudReader.isRemote(actual));assertNull(actual.jsLib)
            }
            val queries=fixture.gateway.calls.filter{it.path=="/v1/sources"}
            assertTrue(queries.all{it.query["active"]=="true"})
            assertTrue(queries.any{it.query["cursor"]=="second"})
            assertEquals(2,CloudCatalogue.exploreRoutes(null).size)
            assertEquals("reader-cloud://category/urban",CloudCatalogue.exploreRoutes("都市").single().url)
            val requests=fixture.gateway.calls.size
            runBlocking{CloudCatalogue.refresh("books")}
            assertEquals("Warm catalogue reuse must avoid repeating metadata pages",requests,fixture.gateway.calls.size)

            withMain(fixture){activity->
                open(activity,R.id.menu_discovery)
                await{texts(activity.window.decorView).containsAll(listOf(fixture.bookName(0),fixture.bookName(1)))}
                fun select(key:String)=main{
                    val parent=activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().first()
                    val tabs=parent.requireView().findViewById<HorizontalCategoryView>(R.id.aggregation_categories)
                    assertEquals("Only All and two merged categories are exposed",3,tabs.adapter!!.itemCount)
                    check(tabs.positionOf(key) >= 0)
                    tabs.selectCategory(key)
                }
                select("都市")
                await{texts(activity.window.decorView).contains(fixture.bookName(0)) && !texts(activity.window.decorView).contains(fixture.bookName(1))}
                select(AggregationPolicy.ALL_SOURCES)
                await{texts(activity.window.decorView).containsAll(listOf(fixture.bookName(0),fixture.bookName(1)))}
                assertTrue(fixture.gateway.calls.filter{it.path=="/v1/books/explore"}.any{it.body["url"].asString=="reader-cloud://category/urban"})
                assertTrue(fixture.gateway.calls.none{it.path.startsWith("/owned-source")})
            }
        }
    }

    @Test(timeout=40_000)
    fun aShortCloudRssPageKeepsItsServerNextPagePendingThroughRealMainLayout() {
        CloudIntegrationFixture(sameBook=false,includeRss=true).use{fixture->
            runBlocking{CloudCatalogue.refresh("rss",force=true)}
            val source=appDb.rssSourceDao.getByKey(fixture.feedId)!!
            assertTrue(CloudReader.isRemote(source))
            assertEquals("全部::reader-cloud://articles",source.sortUrl)
            withMain(fixture){activity->
                open(activity,R.id.menu_rss)
                await{texts(activity.window.decorView).contains(fixture.articleTitle)}
                await{
                    val parent=activity.supportFragmentManager.fragments.filterIsInstance<RssFragment>().firstOrNull()?:return@await false
                    val child=parent.childFragmentManager.fragments.filterIsInstance<RssArticlesFragment>().firstOrNull{!it.isHidden}?:return@await false
                    !ViewModelProvider(child)[RssArticlesViewModel::class.java].isLoading
                }
                val first=fixture.gateway.calls.filter{it.path=="/v1/rss/articles"}
                assertEquals(1,first.size);assertEquals(1,first.single().body["page"].asInt)
                main{
                    val parent=activity.supportFragmentManager.fragments.filterIsInstance<RssFragment>().first()
                    val child=parent.childFragmentManager.fragments.filterIsInstance<RssArticlesFragment>().first{!it.isHidden}
                    child.requireView().findViewById<RecyclerView>(R.id.recycler_view).requestLayout()
                    activity.window.decorView.requestLayout()
                }
                SystemClock.sleep(350)
                assertEquals("A layout callback is not a request to read the next article page",first.size,fixture.gateway.calls.count{it.path=="/v1/rss/articles"})
                assertTrue(texts(activity.window.decorView).contains(fixture.articleTitle))
            }
        }
    }
}
