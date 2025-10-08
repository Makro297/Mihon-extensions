package eu.kanade.tachiyomi.extension.vi.vcomycs

import android.content.SharedPreferences
import android.util.Base64
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
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Vcomycs : ParsedHttpSource(), ConfigurableSource {

    override val name: String = "Vcomycs"

    private val defaultBaseUrl: String = "https://vivicomi7.info"

    override val lang: String = "vi"

    override val supportsLatest: Boolean = false

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Android) Mihon-Vcomycs")

    // ========= Popular Manga =========
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun popularMangaSelector() = ".comic-list .comic-item:not(.grayscale-img)"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select(".comic-title-link a").attr("href"))
        title = element.select(".comic-title").text().trim()
        thumbnail_url = element.select(".img-thumbnail").attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = "li.next:not(.disabled)"

    // ========= Latest Updates (not supported) =========
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // ========= Search =========
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
        val responseBody = response.body.string()

        if (responseBody.isBlank()) {
            return MangasPage(emptyList(), false)
        }

        val dto = responseBody.parseAs<SearchResponseDto>()

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

    // ========= Manga Details =========
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select(".info-title").text()
        author = document.select(".comic-info strong:contains(Tác giả) + span").text().trim()
        description = document.select(".intro-container .text-justify").text().substringBefore("— Xem Thêm —")
        genre = document.select(".comic-info .tags a").joinToString { tag ->
            tag.text().split(' ').joinToString(separator = " ") { word ->
                word.replaceFirstChar { it.titlecase() }
            }
        }
        thumbnail_url = document.select(".img-thumbnail").attr("abs:src")

        val statusString = document.select(".comic-info strong:contains(Tình trạng) + span").text()
        status = when (statusString) {
            "Đang tiến hành" -> SManga.ONGOING
            "Trọn bộ " -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // ========= Chapter List =========
    override fun chapterListSelector(): String = ".chapter-table table tbody tr"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        name = element.select("a .hidden-sm").text()
        date_upload = runCatching {
            dateFormat.parse(element.select("td").last()!!.text())?.time
        }.getOrNull() ?: 0
    }

    // ========= Page List & Image Decryption =========
    override fun pageListParse(document: Document): List<Page> {
        val raw = document.html()

        // 1) Bắt biến htmlContent = "....";
        val regex = Regex("""htmlContent\s*=\s*(".*?");""")
        val match = regex.find(raw)
            ?: throw Exception("Không tìm thấy htmlContent trong trang chapter")

        // 2) Unescape JSON string và parse EncData
        val jsonStringRaw = match.groupValues[1]
        val jsonString = jsonStringRaw
            .trim()
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")

        val encData = jsonString.parseAs<EncData>()

        // 3) Giải mã AES-256-CBC với PBKDF2-HMAC-SHA512
        val decryptedHtml = decryptChapterHtml(encData)

        // 4) Thay token → ký tự thật
        val fixedHtml = decryptedHtml
            .replace("EhwuFp", ".")
            .replace("SJkhMV", ":")
            .replace("uUPzrw", "/")

        // 5) Parse ảnh từ data-ehwufp
        val imgDoc = Jsoup.parse(fixedHtml, baseUrl)
        val images = imgDoc.select("img[data-ehwufp], img[data-src], img[src]")

        if (images.isEmpty()) {
            throw Exception("Không tìm thấy ảnh nào trong chapter")
        }

        return images.mapIndexed { index, img ->
            val url = img.attr("data-ehwufp").ifBlank {
                img.attr("data-src").ifBlank {
                    img.absUrl("src")
                }
            }
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    // ========= Decryption Helper =========
    private fun decryptChapterHtml(d: EncData): String {
        val passphrase = "EhwuFp" + "SJkhMV" + "uUPzrw" // ghép chuỗi như mô tả cộng đồng

        val saltBytes = d.salt.hexToBytes()
        val ivBytes = d.iv.hexToBytes()
        val cipherBytes = Base64.decode(d.ciphertext, Base64.DEFAULT)

        // PBKDF2-HMAC-SHA512 -> 32 bytes key, 999 iterations
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(passphrase.toCharArray(), saltBytes, 999, 256)
        val keyBytes = skf.generateSecret(spec).encoded
        val key = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ivBytes))
        val plaintext = cipher.doFinal(cipherBytes)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = ((clean[i].digitToInt(16) shl 4) + clean[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }

    // ========= Preferences =========
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

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }

        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
