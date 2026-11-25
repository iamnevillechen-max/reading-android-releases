package io.legado.app.help.book

import io.legado.app.constant.BookType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTextBookPolicyTest {
    @Test fun recognizesOnlyUntypedBooksWithBothNetworkIdentities() {
        assertTrue(LegacyTextBookPolicy.isNetworkText(0,
            "https://source.invalid/#reader-verified", "https://source.invalid/book/來信"))
        assertTrue(LegacyTextBookPolicy.isNetworkText(0,
            "HTTP://127.0.0.1:8080/source", "http://127.0.0.1:8080/book"))
        for (origin in listOf("", "loc_book", "webDav::https://files.invalid/book.txt",
            "file:///books/book.txt", "reader-cloud://source", "source-name", "https://")) {
            assertFalse(origin, LegacyTextBookPolicy.isNetworkText(0, origin, "https://source.invalid/book"))
        }
        for (url in listOf("", "/book/1", "file:///books/book.txt", "content://books/1",
            "javascript:alert(1)", "https://", "https://source.invalid/bad path")) {
            assertFalse(url, LegacyTextBookPolicy.isNetworkText(0, "https://source.invalid", url))
        }
    }

    @Test fun neverReinterpretsAnExplicitTypeAsLegacyText() {
        for (type in listOf(BookType.text, BookType.local, BookType.audio, BookType.image,
            BookType.webFile, BookType.archive, BookType.local or BookType.text,
            BookType.audio or BookType.updateError, BookType.image or BookType.notShelf,
            1, 2, Int.MIN_VALUE, -1)) {
            assertFalse(type.toString(), LegacyTextBookPolicy.isNetworkText(type,
                "https://source.invalid", "https://source.invalid/book"))
        }
    }

    @Test fun metadataFlagsDoNotHideLegacyNetworkTextIdentity() {
        for (type in listOf(BookType.updateError, BookType.notShelf,
            BookType.updateError or BookType.notShelf)) {
            assertTrue(type.toString(), LegacyTextBookPolicy.isNetworkText(type,
                "https://source.invalid", "https://source.invalid/book"))
            assertFalse(LegacyTextBookPolicy.isNetworkText(type, "loc_book", "https://source.invalid/book"))
            assertFalse(LegacyTextBookPolicy.isNetworkText(type, "https://source.invalid", "file:///book.txt"))
        }
    }
}
