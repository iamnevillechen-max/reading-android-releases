package io.legado.app.ui.book.toc

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

class TocActivityResult : ActivityResultContract<String, Triple<Int, Int, Boolean>?>() {

    var bookmarkQuote: String? = null
        private set

    override fun createIntent(context: Context, input: String): Intent {
        return Intent(context, TocActivity::class.java)
            .putExtra("bookUrl", input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Triple<Int, Int, Boolean>? {
        bookmarkQuote = null
        if (resultCode == RESULT_OK) {
            intent?.let {
                bookmarkQuote = it.getStringExtra("bookmarkQuote")
                return Triple(
                    it.getIntExtra("index", 0),
                    it.getIntExtra("chapterPos", 0),
                    it.getBooleanExtra("chapterChanged", false)
                )
            }
        }
        return null
    }
}