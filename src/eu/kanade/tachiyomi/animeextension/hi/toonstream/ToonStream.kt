package eu.kanade.tachiyomi.animeextension.hi.toonstream

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
import java.text.SimpleDateFormat
import java.util.Locale

class ToonStream : ParsedAnimeHttpSource() {

    override val name = "ToonStream"
    override val baseUrl = "https://toonstream.vip"
    override val lang = "hi"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val dateFormat = SimpleDateFormat("yyyy", Locale.ENGLISH)

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )

    // Fix image URL — site uses protocol-relative URLs like //image.tmdb.org/...
    private fun fixImageUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> "$baseUrl$url"
        }
    }

    // ======================== Popular ========================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/category/anime/anime-series/"
        } else {
            "$baseUrl/category/anime/anime-series/page/$page/"
        }
        return GET(url, headers)
    }

    // Articles have class like "post dfx fcl series" or "post dfx fcl movies"
    override fun popularAnimeSelector() = "article.post"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val link = element.selectFirst("a.lnk-blk")!!
            setUrlWithoutDomain(link.attr("href"))
            title = element.selectFirst("h2.entry-title")?.text()
                ?: link.attr("title").ifEmpty { "Unknown" }
            thumbnail_url = element.selectFirst("img")?.let {
                val src = it.attr("src").ifEmpty { it.attr("data-src") }
                fixImageUrl(src)
            }
        }
    }

    // WordPress pagination — next page link has rel="next"
    override fun popularAnimeNextPageSelector() =
        "a[rel=\"next\"], .pagination .next, .nav-links a.next, a.next-posts-link"

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/category/anime/anime-series/"
        } else {
            "$baseUrl/category/anime/anime-series/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ======================== Search ========================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            val searchUrl = if (page == 1) {
                "$baseUrl/?s=${query.replace(" ", "+")}"
            } else {
                "$baseUrl/page/$page/?s=${query.replace(" ", "+")}"
            }
            GET(searchUrl, headers)
        } else {
            // Filter-based browsing
            var categoryUrl = "$baseUrl/"
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        if (filter.state != 0) {
                            categoryUrl = "$baseUrl/category/${filter.slugs[filter.state].second}/"
                        }
                    }
                    is TypeFilter -> {
                        if (filter.state != 0) {
                            categoryUrl = "$baseUrl/category/anime/${filter.slugs[filter.state].second}/"
                        }
                    }
                    else -> {}
                }
            }
            if (page > 1) categoryUrl += "page/$page/"
            GET(categoryUrl, headers)
        }
    }

    override fun searchAnimeSelector() = "article.post"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ======================== Details ========================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst(
                "h1.entry-title, h1.Title, .sheader h1, .entry-header h1",
            )?.text() ?: ""
            thumbnail_url = document.selectFirst(
                ".post-thumbnail img, .wp-post-image, img.attachment-post-thumbnail",
            )?.let { fixImageUrl(it.attr("src").ifEmpty { it.attr("data-src") }) }
            description = document.selectFirst(
                ".entry-content p, .description p, .sinopsis p",
            )?.text()
            genre = document.select(
                ".entry-meta a[href*=\"/category/\"], .tags a, .genres a",
            ).joinToString { it.text() }
            status = when {
                document.selectFirst("a[href*=\"ongoing\"]") != null -> SAnime.ONGOING
                document.selectFirst("a[href*=\"completed\"]") != null -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ======================== Episodes ========================

    // Series pages list episodes as: li > article.post.episodes > a.lnk-blk
    override fun episodeListSelector() = "li article.post, li > article"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector())
        if (episodes.isEmpty()) {
            // Treat as a movie/single episode
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "Watch"
                    episode_number = 1F
                },
            )
        }
        return episodes.map { episodeFromElement(it) }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val link = element.selectFirst("a.lnk-blk, a")!!
            setUrlWithoutDomain(link.attr("href"))
            // Title is like "Naruto Shippūden 1x1" — use as episode name
            val rawTitle = element.selectFirst("h2.entry-title")?.text() ?: link.text()
            name = rawTitle.ifEmpty { "Episode" }
            // Extract episode number from "SxE" pattern or trailing digits
            val sxe = Regex("""(\d+)x(\d+)""").find(rawTitle)
            episode_number = if (sxe != null) {
                sxe.groupValues[2].toFloatOrNull() ?: 0F
            } else {
                Regex("""\d+""").findAll(rawTitle).lastOrNull()?.value?.toFloatOrNull() ?: 0F
            }
            date_upload = runCatching {
                val timeText = element.selectFirst(".time, .date, span.time")?.text() ?: ""
                val year = Regex("""\d{4}""").find(timeText)?.value ?: ""
                if (year.isNotEmpty()) dateFormat.parse(year)?.time ?: 0L else 0L
            }.getOrDefault(0L)
        }
    }

    // ======================== Videos ========================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // The episode page may have server tabs with data attributes
        // or direct iframes
        val serverItems = document.select(
            "#playeroptionsul li, .dooplay_player_option, [data-post][data-nume]",
        )

        if (serverItems.isNotEmpty()) {
            serverItems.forEach { item ->
                val label = item.selectFirst(".server, .name, span")?.text()
                    ?: item.text().trim()
                val dataId = item.attr("data-post")
                val dataNume = item.attr("data-nume")
                val dataType = item.attr("data-type").ifEmpty { "1" }
                if (dataId.isNotBlank() && dataNume.isNotBlank()) {
                    runCatching {
                        val ajaxBody = okhttp3.FormBody.Builder()
                            .add("action", "doo_player_ajax")
                            .add("post", dataId)
                            .add("nume", dataNume)
                            .add("type", dataType)
                            .build()
                        val ajaxResp = client.newCall(
                            okhttp3.Request.Builder()
                                .url("$baseUrl/wp-admin/admin-ajax.php")
                                .post(ajaxBody)
                                .headers(headers)
                                .addHeader("X-Requested-With", "XMLHttpRequest")
                                .build(),
                        ).execute()
                        val body = ajaxResp.body.string()
                        val embedUrl = Regex(""""embed_url"\s*:\s*"([^"]+)"""")
                            .find(body)?.groupValues?.get(1)
                            ?.replace("\\/", "/") ?: ""
                        if (embedUrl.isNotBlank()) {
                            videoList.addAll(extractVideos(embedUrl, label))
                        }
                    }
                }
            }
        }

        // Fallback: direct iframes
        if (videoList.isEmpty()) {
            document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotBlank() && "about:blank" !in src) {
                    videoList.addAll(extractVideos(src, ""))
                }
            }
        }

        // Last fallback: look for video source directly
        if (videoList.isEmpty()) {
            document.select("video source[src]").forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank()) {
                    videoList.add(Video(src, "Direct", src))
                }
            }
        }

        return videoList.sortedWith(
            compareByDescending<Video> {
                it.quality.contains("hindi", ignoreCase = true) ||
                    it.quality.contains("dub", ignoreCase = true)
            }.thenByDescending { it.quality },
        )
    }

    private fun extractVideos(url: String, langHint: String): List<Video> {
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

    // ======================== Filters ========================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Leave search empty to use filters"),
        GenreFilter(),
        TypeFilter(),
    )

    class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf(
            "All", "Action", "Adventure", "Animation", "Comedy", "Crime",
            "Drama", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi",
            "Thriller",
        ),
    ) {
        val slugs = arrayOf(
            Pair("All", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Animation", "animation"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("Horror", "horror"),
            Pair("Mystery", "mystery"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Thriller", "thriller"),
        )
    }

    class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        arrayOf("All", "Anime Series", "Cartoon"),
    ) {
        val slugs = arrayOf(
            Pair("All", ""),
            Pair("Anime Series", "anime-series"),
            Pair("Cartoon", "cartoon"),
        )
    }
}
