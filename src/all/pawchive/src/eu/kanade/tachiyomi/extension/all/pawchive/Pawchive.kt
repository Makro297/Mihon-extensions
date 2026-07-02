package eu.kanade.tachiyomi.extension.all.pawchive

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.kemono.Kemono
import eu.kanade.tachiyomi.multisrc.kemono.KemonoPostDto
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Pawchive : Kemono("Pawchive", "https://pawchive.st", "all") {

    private val json: Json by injectLazy()
    private val preferences: android.content.SharedPreferences
        get() = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_DEFAULT = "https://pawchive.st"
        private const val CDN_PROXY_PREF = "cdnProxyPref"
        private const val RESTART_TACHIYOMI = ". Restart Mihon to apply new Base URL."
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, BASE_URL_DEFAULT)!!

    override val getTypes = listOf(
        "Patreon",
        "Pixiv Fanbox",
        "Discord",
        "Fantia",
        "Afdian",
        "Boosty",
        "Gumroad",
        "SubscribeStar",
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Override Base URL"
            summary = "Default: $BASE_URL_DEFAULT\nCurrent: ${preferences.getString(BASE_URL_PREF, BASE_URL_DEFAULT)}\n$RESTART_TACHIYOMI"
            setDefaultValue(BASE_URL_DEFAULT)
            dialogTitle = "Override Base URL"

            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = (newValue as String).trimEnd('/')
                preferences.edit().putString(BASE_URL_PREF, newUrl).apply()
                Toast.makeText(screen.context, "Restart Mihon to apply new Base URL.", Toast.LENGTH_LONG).show()
                true
            }
        }
        val cdnProxyPref = ListPreference(screen.context).apply {
            key = CDN_PROXY_PREF
            title = "Image CDN Proxy"
            entries = arrayOf("None (Direct)", "wsrv.nl", "0ms.dev")
            entryValues = arrayOf("", "https://wsrv.nl/?url=", "https://0ms.dev/")
            summary = "%s\nRoutes images through a CDN to speed up loading. Keeps original format for max sharpness."
            setDefaultValue("")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(CDN_PROXY_PREF, newValue as String).apply()
                true
            }
        }

        screen.addPreference(baseUrlPref)
        screen.addPreference(cdnProxyPref)
        super.setupPreferenceScreen(screen)
    }

    private fun fixMangaUrl(manga: SManga): SManga {
        manga.thumbnail_url = manga.thumbnail_url?.replace("img.", "")
        return manga
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = super.fetchPopularManga(page).map { mangasPage ->
        MangasPage(mangasPage.mangas.map { fixMangaUrl(it) }, mangasPage.hasNextPage)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = super.fetchSearchManga(page, query, filters).map { mangasPage ->
        MangasPage(mangasPage.mangas.map { fixMangaUrl(it) }, mangasPage.hasNextPage)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = super.fetchLatestUpdates(page).map { mangasPage ->
        MangasPage(mangasPage.mangas.map { fixMangaUrl(it) }, mangasPage.hasNextPage)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.just(fixMangaUrl(manga))
    }

    private val fileCdnUrl: String
        get() = baseUrl.replace("//", "//file.")

    private val cdnProxyPrefix: String
        get() = preferences.getString(CDN_PROXY_PREF, "")!!

    override fun pageListParse(response: Response): List<Page> {
        val postData = json.decodeFromString<KemonoPostDto>(response.body!!.string())
        return postData.images.mapIndexed { i, path ->
            val cleanPath = path.substringBefore("?")
            val originalUrl = if (cleanPath.startsWith("http")) cleanPath else "$fileCdnUrl/data$cleanPath"
            val url = if (cdnProxyPrefix.isNotEmpty()) "$cdnProxyPrefix$originalUrl" else originalUrl
            Page(i, imageUrl = url)
        }
    }
}
