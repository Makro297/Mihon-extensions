package eu.kanade.tachiyomi.extension.vi.thedarklordsconfession

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class TheDarkLordsConfession : HttpSource() {

    override val name = "The Dark Lord's Confession"
    override val baseUrl = "https://thedarklordsconfession.io.vn"
    override val lang = "vi"
    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    // Format ngày từ bundle: "06.07.2022"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

    // Regex extract chapter từ JS bundle
    // Ví dụ: {id:145,title:"Hồi Kết...",date:"05.06.2026",rating:9.9,thumbnail:"...",bgm:null}
    private val chapterRegex = Regex(
        """\{id:(\d+),title:"([^"]+)",date:"(\d{2}\.\d{2}\.\d{4})",rating:[0-9.]+,thumbnail:"([^"]+)",bgm:(?:null|"[^"]*")\}""",
    )

    // Regex tìm URL bundle JS từ HTML
    private val bundleUrlRegex = Regex("""src="(/assets/index-[^"]+\.js)"""")

    // ========================= Headers =========================

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ===================== Popular Manga =======================
    // Chỉ có 1 manga duy nhất

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.just(MangasPage(listOf(createManga()), false))
    }

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ===================== Latest Updates ======================

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.just(MangasPage(listOf(createManga()), false))
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ====================== Search Manga =======================

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        // Xử lý deep link: query = "id:145" → navigate chapter
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val chapterId = query.removePrefix(PREFIX_ID_SEARCH).trim()
            val manga = createManga().apply {
                url = "/chapters/$chapterId" // Set URL tạm để UrlActivity có thể navigate tới chapter list
            }
            return Observable.just(MangasPage(listOf(manga), false))
        }
        val manga = createManga()
        val matches = query.isBlank() ||
            manga.title.contains(query, ignoreCase = true)
        return Observable.just(
            MangasPage(if (matches) listOf(manga) else emptyList(), false),
        )
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ==================== Manga Details ========================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.just(createManga())
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(baseUrl, headers)

    override fun mangaDetailsParse(response: Response): SManga = createManga()

    // =================== Chapter List ==========================
    // Bước 1: Fetch HTML → lấy URL bundle JS
    // Bước 2: Fetch bundle → regex extract chapter list

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(GET(baseUrl, headers))
            .asObservableSuccess()
            .map { response -> parseBundleUrlFromHtml(response) }
            .flatMap { bundleUrl ->
                client.newCall(GET(bundleUrl, headers)).asObservableSuccess()
            }
            .map { response -> parseChaptersFromBundle(response) }
    }

    private fun parseBundleUrlFromHtml(response: Response): String {
        val html = response.body.string()
        val match = bundleUrlRegex.find(html)
            ?: throw Exception("Không tìm thấy JS bundle URL trong HTML")
        return baseUrl + match.groupValues[1]
    }

    private fun parseChaptersFromBundle(response: Response): List<SChapter> {
        val jsContent = response.body.string()
        val chapters = chapterRegex.findAll(jsContent).map { match ->
            val id = match.groupValues[1].toInt()
            val title = match.groupValues[2]
            val dateStr = match.groupValues[3]
            SChapter.create().apply {
                url = "/chapters/$id"
                name = "Chapter $id: $title"
                chapter_number = id.toFloat()
                date_upload = runCatching {
                    dateFormat.parse(dateStr)?.time
                }.getOrNull() ?: 0L
            }
        }.toList()

        if (chapters.isEmpty()) {
            throw Exception("Không parse được chapter nào từ bundle")
        }

        // Sắp xếp từ mới nhất → cũ nhất (Mihon convention)
        return chapters.sortedByDescending { it.chapter_number }
    }

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // ==================== Page List ============================
    // Fetch /data/chapter-{id}.json → parse mảng tên file webp

    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url = "/chapters/{id}"
        val chapterId = chapter.url.substringAfterLast("/")
        return GET("$baseUrl/data/chapter-$chapterId.json", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        // Lấy chapterId từ URL request: /data/chapter-{id}.json
        val chapterId = response.request.url.pathSegments.last()
            .removePrefix("chapter-").removeSuffix(".json")

        val filenames = json.parseToJsonElement(response.body.string()).jsonArray

        return filenames.mapIndexed { index, element ->
            val filename = element.jsonPrimitive.content
            // Khoảng trắng trong tên thư mục phải encode thành %20
            val imageUrl = "$baseUrl/images/chapters/chapter%20$chapterId/panels/$filename"
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ==================== Filters ==============================
    override fun getFilterList() = FilterList()

    // ==================== Helpers ==============================

    private fun createManga() = SManga.create().apply {
        url = "/"
        title = "The Dark Lord's Confession"
        // Cover = thumbnail chapter 1
        thumbnail_url = "$baseUrl/images/chapters/chapter%201/thumbnail/thumbnail.jpg"
        description = "Trong thế giới bị phủ đầy bởi những sự thật giả dối, " +
            "bí mật của Chúa Tể Bóng Tối sẽ thay đổi vận mệnh của cả lục địa. " +
            "Một câu truyện về tình yêu nghiệt ngã, phép thuật cổ xưa và hành trình " +
            "tìm lại sự cứu rỗi."
        author = "The Dark Lord's Confession Team"
        artist = "The Dark Lord's Confession Team"
        genre = "Fantasy, Romance, Drama"
        status = SManga.ONGOING
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }
}
