package eu.kanade.tachiyomi.extension.vi.sangchanhteam

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SangChanhTeam : ParsedHttpSource(), ConfigurableSource {

    override val name = "Sang Chanh Team"

    private val defaultBaseUrl = "https://sangchanhteam.com"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "vi"

    // Popular = trang Mới cập nhật (supportsLatest = false vì popular đã là mới cập nhật)
    override val supportsLatest = false

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

    // ========================= Headers =========================

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ===================== Popular Manga =======================
    // Popular = Mới cập nhật tại /moi-cap-nhat/page/N/

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/moi-cap-nhat/page/$page/", headers)

    override fun popularMangaSelector() = "div.manga-item-grid"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val linkEl = element.selectFirst("h2 a.uk-link-heading")
            ?: element.selectFirst("h2 a")!!
        setUrlWithoutDomain(linkEl.attr("href"))
        title = linkEl.text().trim()
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = "ul.uk-pagination li.uk-active + li:not(.uk-disabled) a"

    // ===================== Latest Updates =======================
    // Không hỗ trợ (popular đã là mới cập nhật)

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // ===================== Search Manga ========================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            // Xử lý deep link
            query.startsWith(PREFIX_ID_SEARCH) -> {
                val slug = query.removePrefix(PREFIX_ID_SEARCH).trim()
                fetchMangaDetails(
                    SManga.create().apply { url = "/truyen/$slug/" },
                ).map {
                    it.url = "/truyen/$slug/"
                    MangasPage(listOf(it), false)
                }
            }

            // Tìm kiếm bằng từ khóa (ưu tiên keyword nếu user nhập)
            query.isNotBlank() -> {
                val url = "$baseUrl/page/$page/?s=${query.trim()}"
                client.newCall(GET(url, headers))
                    .asObservableSuccess()
                    .map { response ->
                        val doc = response.asJsoup()
                        // WordPress search results uses article.uk-grid-small
                        val mangas = doc.select("article.uk-grid-small").map {
                            SManga.create().apply {
                                val titleEl = it.selectFirst(".uk-link-heading")
                                title = titleEl?.text()?.trim() ?: ""
                                setUrlWithoutDomain(titleEl?.attr("href") ?: "")
                                thumbnail_url = it.selectFirst("img.wp-post-image")?.attr("abs:src")
                            }
                        }
                        val hasNextPage = doc.selectFirst("a.next.page-numbers") != null || doc.selectFirst("ul.uk-pagination li a[aria-label=Next]") != null
                        MangasPage(mangas, hasNextPage)
                    }
            }

            // Tìm kiếm khi không nhập từ khóa (dùng bộ lọc)
            else -> super.fetchSearchManga(page, query, filters)
        }
    }

    // Khi có bộ lọc → dùng /bo-loc-nang-cao/ với query params
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = "$baseUrl/bo-loc-nang-cao/".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        // Thể loại (multi-select)
        filters.filterIsInstance<GenreFilter>().firstOrNull()?.state?.forEach { genre ->
            if (genre.state) {
                urlBuilder.addQueryParameter("genre[]", genre.id)
            }
        }

        // Các filter đơn (select)
        filters.filterIsInstance<UriPartFilter>().forEach { filter ->
            val value = filter.toUriPart()
            if (value.isNotEmpty()) {
                urlBuilder.addQueryParameter(filter.queryParam, value)
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    // Trang bộ lọc dùng layout manga-item-details
    override fun searchMangaSelector() = "div.manga-item-details, div.manga-item-grid"

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ==================== Manga Details ========================

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1#manga-title")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("div.story-cover-wrap img")?.attr("abs:src")
        description = document.selectFirst("div#manga-description")?.text()?.trim()
        genre = document.select("div#genre-tags a").joinToString { it.text().trim() }
        // author: để trống (web không có trường tác giả)
        author = ""
        // Nhóm dịch lấy từ div.manga-info-details
        artist = document.selectFirst("div.manga-info-details a[href*='/nhom/']")?.text()?.trim() ?: ""

        val statusText = document.selectFirst("span#manga-status")?.text()?.trim() ?: ""
        status = when {
            statusText.contains("Đang tiến hành", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Trọn bộ", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Nguồn tạm ngưng", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Kết thúc mùa", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Đã theo kịp", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Bị hủy", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // =================== Chapter List ==========================
    // Chapter list nằm ở {manga-url}/chap/page/N/ (phân trang riêng)
    // Phải override fetchChapterList để loop qua tất cả các trang

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val mangaUrl = baseUrl + manga.url.trimEnd('/')
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasNextPage = true

        while (hasNextPage) {
            val url = "$mangaUrl/chap/page/$page/"
            val response = client.newCall(GET(url, headers)).execute()
            val doc = response.asJsoup()

            val chapterItems = doc.select(chapterListSelector())
            if (chapterItems.isEmpty()) break

            chapters.addAll(chapterItems.map { chapterFromElement(it) })

            // Kiểm tra có trang tiếp theo không
            hasNextPage = doc.selectFirst(chapterListNextPageSelector()) != null
            page++
        }

        // Trả về từ mới nhất → cũ nhất (Mihon convention)
        return Observable.just(chapters)
    }

    override fun chapterListSelector() = "div.chapter-list div.chapter-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val link = element.selectFirst("a.uk-link-toggle")!!
        setUrlWithoutDomain(link.attr("href"))
        name = element.selectFirst("h3.uk-link-heading")?.text()?.trim()
            ?: element.selectFirst("h3")?.text()?.trim()
            ?: link.text().trim()
        date_upload = element.selectFirst("time")?.attr("datetime")
            ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
    }

    // Selector trang tiếp theo trong danh sách chapter
    private fun chapterListNextPageSelector() = popularMangaNextPageSelector()

    // ==================== Page List ============================
    // Ảnh trong chapter không bị encrypt — load thẳng từ src

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#chapter-content img")
            .filterNot { it.hasClass("attachment-full") } // bỏ ảnh quảng cáo/header
            .mapIndexed { idx, img ->
                val imageUrl = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
                Page(idx, imageUrl = imageUrl)
            }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // ==================== Filters ==============================

    override fun getFilterList() = SangChanhTeamFilters.getFilterList()

    // =================== Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Mặc định: $defaultBaseUrl"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String =
        preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    // ==================== Utilities ============================

    companion object {
        const val PREFIX_ID_SEARCH = "id:"

        // Datetime format từ attr datetime="2026-06-01T00:24:53+07:00" (ISO 8601)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
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
