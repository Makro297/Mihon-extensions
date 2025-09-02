package eu.kanade.tachiyomi.extension.vi.vcomycs

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Vcomycs : ParsedHttpSource(), ConfigurableSource {

    override val name: String = "Vcomycs"

    private val defaultBaseUrl: String = "https://vivicomi6.info/"

    override val lang: String = "vi"

    override val supportsLatest: Boolean = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences = getPreferences()

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    override val baseUrl by lazy { getPrefBaseUrl() }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun popularMangaSelector() = ".comic-list .comic-item:not(.grayscale-img)"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select(".comic-title-link a").attr("href"))
        title = element.select(".comic-title").text().trim()
        thumbnail_url = element.select(".img-thumbnail").attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = "li.next:not(.disabled)"

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_SEARCH) -> {
                val id = query.removePrefix(PREFIX_ID_SEARCH).trim()
                fetchMangaDetails(
                    SManga.create().apply {
                        url = "/truyen-tranh/$id/"
                    },
                )
                    .map {
                        it.url = "/truyen-tranh/$id/"
                        MangasPage(listOf(it), false)
                    }
            }
            else -> super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        POST(
            "$baseUrl/wp-admin/admin-ajax.php",
            headers,
            FormBody.Builder()
                .add("action", "searchtax")
                .add("keyword", query)
                .build(),
        )

    override fun searchMangaSelector(): String = throw UnsupportedOperationException()

    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException()

    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchResponseDto>()

        if (!dto.success) {
            return MangasPage(emptyList(), false)
        }

        val manga = dto.data
            .map {
                SManga.create().apply {
                    setUrlWithoutDomain(it.link)
                    title = it.title
                    thumbnail_url = it.img
                }
            }

        return MangasPage(manga, false)
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        Log.d("Vcomycs-Details", "=== Parsing manga details ===")
        Log.d("Vcomycs-Details", "Document title: ${document.title()}")
        Log.d("Vcomycs-Details", "Document URL: ${document.location()}")
        
        title = document.select(".info-title").text()
        Log.d("Vcomycs-Details", "Title: $title")
        
        author = document.select(".comic-info strong:contains(Tác giả) + span").text().trim()
        Log.d("Vcomycs-Details", "Author: $author")
        
        description = document.select(".intro-container .text-justify").text().substringBefore("— Xem Thêm —")
        Log.d("Vcomycs-Details", "Description length: ${description?.length ?: 0}")
        
        genre = document.select(".comic-info .tags a").joinToString { tag ->
            tag.text().split(' ').joinToString(separator = " ") { word ->
                word.replaceFirstChar { it.titlecase() }
            }
        }
        Log.d("Vcomycs-Details", "Genres: $genre")
        
        thumbnail_url = document.select(".img-thumbnail").attr("abs:src")
        Log.d("Vcomycs-Details", "Thumbnail URL: $thumbnail_url")

        val statusString = document.select(".comic-info strong:contains(Tình trạng) + span").text()
        status = when (statusString) {
            "Đang tiến hành" -> SManga.ONGOING
            "Trọn bộ " -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        Log.d("Vcomycs-Details", "Status: $statusString -> $status")
    }

    override fun chapterListSelector(): String = ".chapter-table table tbody tr"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val chapterUrl = element.select("a").attr("href")
        setUrlWithoutDomain(chapterUrl)
        name = element.select("a .hidden-sm").text()
        date_upload = runCatching {
            dateFormat.parse(element.select("td").last()!!.text())?.time
        }.getOrNull() ?: 0
        
        Log.d("Vcomycs-Chapter", "Chapter: $name | URL: $chapterUrl | Date: $date_upload")
    }

    protected fun decodeImgList(document: Document): String {
        Log.d("Vcomycs-Decrypt", "=== Starting decodeImgList ===")
        Log.d("Vcomycs-Decrypt", "Document title: ${document.title()}")
        Log.d("Vcomycs-Decrypt", "Document URL: ${document.location()}")
        
        // Check for scripts containing image data
        val allScripts = document.select("script")
        Log.d("Vcomycs-Decrypt", "Total scripts found: ${allScripts.size}")
        
        val htmlContentScript = document.selectFirst("script:containsData(htmlContent)")?.html()
            ?.substringAfter("var htmlContent=\"")
            ?.substringBefore("\";")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\/", "/")
            ?: throw Exception("Couldn't find script with image data.")
            
        Log.d("Vcomycs-Decrypt", "Found htmlContent script, length: ${htmlContentScript.length}")
        Log.d("Vcomycs-Decrypt", "htmlContent preview: ${htmlContentScript.take(100)}...")
        
        val htmlContent = htmlContentScript.parseAs<CipherDto>()
        val ciphertext = Base64.decode(htmlContent.ciphertext, Base64.DEFAULT)
        val iv = htmlContent.iv.decodeHex()
        val salt = htmlContent.salt.decodeHex()
        
        Log.d("Vcomycs-Decrypt", "Parsed cipher data - ciphertext length: ${ciphertext.size}, iv length: ${iv.size}, salt length: ${salt.size}")

        val passwordScript = document.selectFirst("script:containsData(chapterHTML)")?.html()
            ?: throw Exception("Couldn't find password to decrypt image data.")
        val passphrase = passwordScript.substringAfter("var chapterHTML=CryptoJSAesDecrypt('")
            .substringBefore("',htmlContent")
            .replace("'+'", "")
            
        Log.d("Vcomycs-Decrypt", "Found passphrase, length: ${passphrase.length}")

        val keyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
        val keyS = SecretKeySpec(keyFactory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(iv))

        val imgListHtml = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        
        Log.d("Vcomycs-Decrypt", "Decryption successful, result length: ${imgListHtml.length}")
        Log.d("Vcomycs-Decrypt", "Decrypted HTML preview: ${imgListHtml.take(200)}...")

        return imgListHtml
    }

    override fun pageListParse(document: Document): List<Page> {
        Log.d("Vcomycs-PageList", "=== Starting pageListParse ===")
        Log.d("Vcomycs-PageList", "Document title: ${document.title()}")
        Log.d("Vcomycs-PageList", "Document URL: ${document.location()}")
        
        try {
            val imgListHtml = decodeImgList(document)
            Log.d("Vcomycs-PageList", "Got decrypted HTML, parsing images...")
            
            val parsedDoc = Jsoup.parseBodyFragment(imgListHtml)
            val images = parsedDoc.select("img")
            Log.d("Vcomycs-PageList", "Found ${images.size} images in decrypted HTML")
            
            val pages = images.mapIndexed { idx, element ->
                val encryptedUrl = element.attributes().find { it.key.startsWith("data") }?.value
                val effectiveUrl = encryptedUrl?.decodeUrl() ?: element.attr("abs:src")
                
                Log.d("Vcomycs-PageList", "Image $idx:")
                Log.d("Vcomycs-PageList", "  - Element: ${element.outerHtml().take(100)}...")
                Log.d("Vcomycs-PageList", "  - Encrypted URL: $encryptedUrl")
                Log.d("Vcomycs-PageList", "  - Effective URL: $effectiveUrl")
                
                Page(idx, imageUrl = effectiveUrl)
            }
            
            Log.d("Vcomycs-PageList", "Successfully created ${pages.size} pages")
            pages.forEachIndexed { idx, page ->
                Log.d("Vcomycs-PageList", "Final Page $idx: ${page.imageUrl}")
            }
            
            return pages
            
        } catch (e: Exception) {
            Log.e("Vcomycs-PageList", "ERROR in pageListParse: ${e.message}")
            Log.e("Vcomycs-PageList", "Stack trace: ${e.stackTraceToString()}")
            throw e
        }
    }

    private fun String.decodeUrl(): String? {
        Log.d("Vcomycs-Decode", "Attempting to decode URL: $this")
        
        // We expect the URL to start with `https://`, where the last 3 characters are encoded.
        // The length of the encoded character is not known, but it is the same across all.
        // Essentially we are looking for the two encoded slashes, which tells us the length.
        val patternIdx = patternsLengthCheck.indexOfFirst { pattern ->
            val matchResult = pattern.find(this)
            val g1 = matchResult?.groupValues?.get(1)
            val g2 = matchResult?.groupValues?.get(2)
            Log.d("Vcomycs-Decode", "Pattern test - g1: '$g1', g2: '$g2', match: ${g1 == g2 && g1 != null}")
            g1 == g2 && g1 != null
        }
        if (patternIdx == -1) {
            Log.d("Vcomycs-Decode", "No matching pattern found for: $this")
            return null
        }
        
        Log.d("Vcomycs-Decode", "Found pattern at index: $patternIdx")

        // With a known length we can predict all the encoded characters.
        // This is a slightly more expensive pattern, hence the separation.
        val matchResult = patternsSubstitution[patternIdx].find(this)
        return matchResult?.destructured?.let { (colon, slash, period) ->
            Log.d("Vcomycs-Decode", "Substitution parts - colon: '$colon', slash: '$slash', period: '$period'")
            val decodedUrl = this
                .replace(colon, ":")
                .replace(slash, "/")
                .replace(period, ".")
            Log.d("Vcomycs-Decode", "Successfully decoded to: $decodedUrl")
            decodedUrl
        } ?: run {
            Log.d("Vcomycs-Decode", "Substitution failed for: $this")
            null
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    // https://stackoverflow.com/a/66614516
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    companion object {
        const val KEY_ALGORITHM = "PBKDF2WithHmacSHA512"
        const val CIPHER_TRANSFORMATION = "AES/CBC/PKCS7PADDING"

        const val PREFIX_ID_SEARCH = "id:"
        val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }

        private val patternsLengthCheck: List<Regex> = (20 downTo 1).map { i ->
            """^https.{$i}(.{$i})(.{$i})""".toRegex()
        }
        private val patternsSubstitution: List<Regex> = (20 downTo 1).map { i ->
            """^https(.{$i})(.{$i}).*(.{$i})(?:webp|jpeg|tiff|.{3})$""".toRegex()
        }

        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
