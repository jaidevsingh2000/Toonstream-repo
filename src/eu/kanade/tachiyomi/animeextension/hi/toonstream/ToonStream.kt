package eu.kanade.tachiyomi.animeextension.hi.toonstream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class ToonStream : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "ToonStream"
    override val baseUrl = "https://toonstream.dad"
    override val lang = "hi"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient

    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/home/page/$page/", headers)
    }

    override fun popularAnimeSelector() = "div.post-cards article, div.items article, article.TPost"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            title = element.selectFirst("h2, h3, .Title, .name")?.text()
                ?: element.selectFirst("a")?.attr("title") ?: ""
            thumbnail_url = element.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.nav-links a.next, .pagination a.next, a.nextpostslink"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/recently-added/page/$page/", headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/?s=${query.replace(" ", "+")}&page=$page"
        } else {
            val genreFilter = filters.findInstance<GenreFilter>()
            val typeFilter = filters.findInstance<TypeFilter>()
            val langFilter = filters.findInstance<LanguageFilter>()
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            genreFilter?.state?.let { idx ->
                if (idx != 0) urlBuilder.addPathSegment("genre").addPathSegment(genreFilter.values[idx].second).addPathSegment("")
            }
            typeFilter?.state?.let { idx ->
                if (idx != 0) urlBuilder.addPathSegment("type").addPathSegment(typeFilter.values[idx].second).addPathSegment("")
            }
            langFilter?.state?.let { idx ->
                if (idx != 0) urlBuilder.addPathSegment("language").addPathSegment(langFilter.values[idx].second).addPathSegment("")
            }
            urlBuilder.addQueryParameter("page", page.toString())
            urlBuilder.build().toString()
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = "div.result-item article, ${popularAnimeSelector()}"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1.Title, h1.entry-title, .sheader h1")?.text() ?: ""
            thumbnail_url = document.selectFirst(".Image img, .poster img, img.TPostMv")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            description = document.selectFirst(".Description p, .sbox.fixidtab p, article.Description")?.text()
            genre = document.select(".Genre a, .sgeneros a").joinToString { it.text() }
            status = when (document.selectFirst(".Status")?.text()?.lowercase()) {
                "ongoing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListSelector() = "ul.episodios li, div.episodios li, .episodelist li, ul.season-list li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector())
        if (episodes.isEmpty()) {
            return listOf(SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Movie"
                episode_number = 1F
            })
        }
        return episodes.map { episodeFromElement(it) }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("href"))
            name = element.selectFirst(".numerando, .Num")?.text()?.let { "Ep $it" }
                ?: link.text().ifEmpty { element.text() }
            episode_number = name.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0F
            date_upload = runCatching {
                dateFormat.parse(element.selectFirst(".Date, .date")?.text() ?: "")?.time ?: 0L
            }.getOrDefault(0L)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val preferredLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val serverItems = document.select(".dooplay_player_option, .server-item, .option-btn, .PlayerTb li, #playeroptionsul li, .tablinks")
        if (serverItems.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotBlank()) videoList.addAll(extractVideos(src, preferredLang))
            }
        } else {
            serverItems.forEach { item ->
                val label = item.text()
                if (preferredLang == "hindi" && !label.contains("hindi", ignoreCase = true) &&
                    serverItems.any { it.text().contains("hindi", ignoreCase = true) }) return@forEach
                val dataId = item.attr("data-post")
                val dataNume = item.attr("data-nume")
                val dataType = item.attr("data-type")
                if (dataId.isNotBlank() && dataNume.isNotBlank()) {
                    try {
                        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
                        val ajaxBody = okhttp3.FormBody.Builder()
                            .add("action", "doo_player_ajax").add("post", dataId)
                            .add("nume", dataNume).add("type", dataType).build()
                        val ajaxResp = client.newCall(okhttp3.Request.Builder().url(ajaxUrl).post(ajaxBody).headers(headers).addHeader("X-Requested-With", "XMLHttpRequest").build()).execute()
                        val embedUrl = Regex(""""embed_url":"([^"]+)"""").find(ajaxResp.body.string())?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                        if (embedUrl.isNotBlank()) videoList.addAll(extractVideos(embedUrl, label))
                    } catch (_: Exception) {}
                }
            }
        }
        return videoList.sortedWith(compareByDescending<Video> { it.quality.contains("hindi", ignoreCase = true) || it.quality.contains("dub", ignoreCase = true) }.thenByDescending { it.quality })
    }

    private fun extractVideos(url: String, langHint: String = ""): List<Video> {
        val extractor = ToonStreamExtractor(client, headers)
        return when {
            "streamtape" in url -> extractor.streamtapeVideos(url, langHint)
            "dood" in url || "ds2play" in url -> extractor.doodVideos(url, langHint)
            "filemoon" in url || "moonplayer" in url -> extractor.filemoonVideos(url, langHint)
            "streamwish" in url || "swish" in url -> extractor.streamwishVideos(url, langHint)
            "voe.sx" in url || "voe." in url -> extractor.voeVideos(url, langHint)
            "upstream" in url || "uprot" in url -> extractor.upstreamVideos(url, langHint)
            "mp4upload" in url -> extractor.mp4uploadVideos(url, langHint)
            else -> extractor.genericVideos(url, langHint)
        }
    }

    override fun videoListSelector() = "not used"
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filters (leave search empty to browse)"),
        LanguageFilter(), GenreFilter(), TypeFilter(),
    )

    class LanguageFilter : AnimeFilter.Select<String>("Language",
        arrayOf("All", "Hindi Dubbed", "English Dubbed", "Japanese (Sub)")
    ) {
        val values = arrayOf(Pair("All",""), Pair("Hindi Dubbed","hindi-dubbed"), Pair("English Dubbed","english-dubbed"), Pair("Japanese (Sub)","japanese"))
    }

    class GenreFilter : AnimeFilter.Select<String>("Genre",
        arrayOf("All","Action","Adventure","Comedy","Drama","Fantasy","Horror","Magic","Martial Arts","Mecha","Music","Mystery","Psychological","Romance","School","Sci-Fi","Slice of Life","Sports","Super Power","Supernatural","Thriller")
    ) {
        val values = arrayOf(Pair("All",""),Pair("Action","action"),Pair("Adventure","adventure"),Pair("Comedy","comedy"),Pair("Drama","drama"),Pair("Fantasy","fantasy"),Pair("Horror","horror"),Pair("Magic","magic"),Pair("Martial Arts","martial-arts"),Pair("Mecha","mecha"),Pair("Music","music"),Pair("Mystery","mystery"),Pair("Psychological","psychological"),Pair("Romance","romance"),Pair("School","school"),Pair("Sci-Fi","sci-fi"),Pair("Slice of Life","slice-of-life"),Pair("Sports","sports"),Pair("Super Power","super-power"),Pair("Supernatural","supernatural"),Pair("Thriller","thriller"))
    }

    class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All","TV Series","Movies","OVA","ONA","Special")) {
        val values = arrayOf(Pair("All",""),Pair("TV Series","tv"),Pair("Movies","movies"),Pair("OVA","ova"),Pair("ONA","ona"),Pair("Special","special"))
    }

    private inline fun <reified T : AnimeFilter<*>> AnimeFilterList.findInstance(): T? = filterIsInstance<T>().firstOrNull()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Language"
            entries = arrayOf("Hindi Dubbed (Preferred)", "English Dubbed", "Any")
            entryValues = arrayOf("hindi", "english", "any")
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "Select your preferred audio language for episodes"
            setOnPreferenceChangeListener { _, newValue -> preferences.edit().putString(PREF_LANG_KEY, newValue as String).apply(); true }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Video Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Highest Available")
            entryValues = arrayOf("1080", "720", "480", "360", "highest")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "Select your preferred video resolution"
            setOnPreferenceChangeListener { _, newValue -> preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).apply(); true }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "hindi"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }
}
