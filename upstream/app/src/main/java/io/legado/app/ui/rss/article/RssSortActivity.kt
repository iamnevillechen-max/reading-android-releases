@file:Suppress("DEPRECATION")

package io.legado.app.ui.rss.article

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.appcompat.app.AlertDialog
import io.legado.app.data.appDb
import io.legado.app.model.aggregation.AggregationPolicy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssArtivlesBinding
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssSortActivity : VMBaseActivity<ActivityRssArtivlesBinding, RssSortViewModel>(),
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityRssArtivlesBinding::inflate)
    override val viewModel by viewModels<RssSortViewModel>()
    private val sourceViewModel by viewModels<io.legado.app.ui.main.rss.RssViewModel>()
    private val adapter by lazy { TabFragmentPageAdapter() }
    private val sortList = mutableListOf<Pair<String, String>>()
    private val fragmentMap = hashMapOf<String, Fragment>()
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.initData(intent) {
                upFragments()
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        fun restoreMode() = binding.aggregationNavigation.root.check(
            if (intent.getStringExtra("url") == AggregationPolicy.ALL_SOURCES) R.id.mode_all else R.id.mode_single)
        restoreMode()
        binding.aggregationNavigation.modeAll.setOnClickListener {
            restoreMode()
            if (intent.getStringExtra("url") != AggregationPolicy.ALL_SOURCES) {
                openSource(AggregationPolicy.ALL_SOURCES)
            }
        }
        binding.aggregationNavigation.modeSingle.setOnClickListener {
            restoreMode()
            showSourceChooser()
        }
        binding.viewPager.adapter = adapter
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        viewModel.titleLiveData.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.initData(intent) {
            upFragments()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_articles, menu)
        menuInflater.inflate(R.menu.aggregation_results, menu)
        menu.findItem(R.id.menu_aggregate_refresh).isVisible = false
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        listOf(R.id.menu_clear, R.id.menu_edit_source, R.id.menu_set_source_variable,
            R.id.menu_switch_layout, R.id.menu_refresh_sort).forEach {
            menu.findItem(it)?.isVisible = !viewModel.isAggregate
        }
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.rssSource?.loginUrl.isNullOrBlank()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_aggregate_sources -> showSourceChooser()
            R.id.menu_login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", viewModel.rssSource?.sourceUrl)
            }

            R.id.menu_refresh_sort -> viewModel.clearSortCache { upFragments() }
            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_edit_source -> viewModel.rssSource?.sourceUrl?.let {
                editSourceResult.launch {
                    putExtra("sourceUrl", it)
                }
            }

            R.id.menu_clear -> {
                viewModel.url?.let {
                    viewModel.clearArticles()
                }
            }

            R.id.menu_switch_layout -> {
                viewModel.switchLayout()
                upFragments()
            }

            R.id.menu_read_record -> {
                showDialogFragment<ReadRecordDialog>()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showSourceChooser() {
        lifecycleScope.launch {
                val sources = withContext(Dispatchers.IO) { appDb.rssSourceDao.all.filter { it.enabled } }
                val labels = listOf(getString(R.string.aggregation_all_rss)) + sources.map { it.sourceName }
                AlertDialog.Builder(this@RssSortActivity).setTitle(R.string.aggregation_choose_sources)
                    .setItems(labels.toTypedArray()) { _, index ->
                        val source = sources.getOrNull(index - 1)
                        if (source?.singleUrl == true) {
                            sourceViewModel.getSingleUrl(source) { url ->
                                if (url.startsWith("http", true)) {
                                    startActivity<io.legado.app.ui.rss.read.ReadRssActivity> {
                                        putExtra("origin", url)
                                        putExtra("title", source.sourceName)
                                    }
                                } else openUrl(url)
                            }
                        } else {
                            openSource(source?.sourceUrl ?: AggregationPolicy.ALL_SOURCES)
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun openSource(url: String) {
        // Do not reuse and then finish this singleTop instance.
        finish()
        startActivity<RssSortActivity> { putExtra("url", url) }
    }

    private fun upFragments() {
        lifecycleScope.launch {
            if (viewModel.isAggregate) {
                sortList.clear()
                sortList.add(getString(R.string.aggregation_all_rss) to AggregationPolicy.ALL_SOURCES)
                binding.tabLayout.gone()
                adapter.notifyDataSetChanged()
                return@launch
            }
            viewModel.rssSource?.sortUrls()?.let {
                sortList.clear()
                sortList.addAll(it)
            }
            if (sortList.size == 1) {
                binding.tabLayout.gone()
            } else {
                binding.tabLayout.visible()
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.rssSource
            if (source == null) {
                toastOnUi("源不存在")
                return@launch
            }
            val comment =
                source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
            val variable = withContext(Dispatchers.IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.rssSource?.setVariable(variable)
    }

    private inner class TabFragmentPageAdapter :
        FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItemPosition(`object`: Any): Int {
            return POSITION_NONE
        }

        override fun getPageTitle(position: Int): CharSequence {
            return sortList[position].first
        }

        override fun getItem(position: Int): Fragment {
            val sort = sortList[position]
            return RssArticlesFragment(sort.first, sort.second)
        }

        override fun getCount(): Int {
            return sortList.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val fragment = super.instantiateItem(container, position) as Fragment
            fragmentMap[sortList[position].first] = fragment
            return fragment
        }
    }

}
