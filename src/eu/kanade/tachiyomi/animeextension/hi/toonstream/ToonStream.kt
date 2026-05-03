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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ToonStream : ParsedAnimeHttpSource() {

    override val name = "ToonStream"
    override val baseUrl = "https://toonstream.vip"
    override val lang = "hi"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )

    // Protocol-relative URLs like //image.tmdb.org need https: prepended
    private fun fixImageUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.isNotBlank() -> "$baseUrl$url"
        else -> ""
    }

    private fun Element.imgUrl(): String {
        val src = attr("src").ifEmpty { attr("data-src").ifEmpty { attr("data-lazy-src") } }
        return fixImageUrl(src)
    }

    // ======================== Popular ========================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/category/anime/anime-series/"
        else "$baseUrl/category/anime/anime-series/page/$page/"
        return GET(url, headers)
    }

    // Article class is "post dfx fcl series" or "post dfx fcl movies" etc.
    override fun popularAnimeSelector() = "article.post"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a.lnk-blk")!!
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst("h2.entry-title")?.text()
            ?: link.attr("title").ifEmpty { "Unknown" }
        thumbnail_url = element.selectFirst(".post-thumbnail img, figure img, img")?.imgUrl()
    }

    // Pages use standard WordPress /page/N/ pagination
    override fun popularAnimeNextPageSelector() =
        "a[rel=\"next\"], .pagination .next, .nav-links a.next, a.next-posts-link"

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/category/anime/anime-series/"
        else "$baseUrl/category/anime/anime-series/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ======================== Search ========================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            val url = if (page == 1) "$baseUrl/?s=${query.replace(" ", "+")}"
            else "$baseUrl/page/$page/?s=${query.replace(" ", "+")}"
            GET(url, headers)
        } else {
            var categoryUrl = "$baseUrl/"
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> if (filter.state != 0) {
                        categoryUrl = "$baseUrl/category/${filter.slugs[filter.state].second}/"
                    }
                    is TypeFilter -> if (filter.state != 0) {
                        categoryUrl = "$baseUrl/category/anime/${filter.slugs[filter.state].second}/"
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

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.entry-title, h1")?.text() ?: ""
        // Poster: prefer w500 TMDB image (series poster) over w185 (episode thumb)
        thumbnail_url = document.select("img[src*='tmdb'], img[src*='as-cdn'], img")
            .firstOrNull { it.attr("src").contains("w500") || it.attr("src").contains("original") }
            ?.imgUrl()
            ?: document.selectFirst(".post-thumbnail img, figure img")?.imgUrl()
        description = document.selectFirst(
            ".entry-content > p, .description p, .sinopsis p, .sbox p",
        )?.text()
        genre = document.select("a[href*='/category/']")
            .filter { it.attr("href").contains("/gener/") || it.attr("href").contains("/action/") || it.attr("href").contains("/genre") }
            .joinToString { it.text() }
            .ifEmpty {
                document.select("a[href*='/category/']")
                    .drop(2) // skip breadcrumb-style first entries
                    .take(5)
                    .joinToString { it.text() }
            }
        status = SAnime.UNKNOWN
    }

    // ======================== Episodes ========================

    // Series page lists episodes as: li > article.post.episodes
    override fun episodeListSelector() = "li article.post"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector())
        if (episodes.isEmpty()) {
            // Single movie / episode page — treat page itself as the episode
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

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val link = element.selectFirst("a.lnk-blk, a")!!
        setUrlWithoutDomain(link.attr("href"))

        val rawTitle = element.selectFirst("h2.entry-title")?.text() ?: ""
        // Title format: "Show Name SxE" e.g. "Naruto Shippūden 1x1"
        val sxeMatch = Regex("""(\d+)x(\d+)""").find(rawTitle)
        if (sxeMatch != null) {
            val season = sxeMatch.groupValues[1]
            val ep = sxeMatch.groupValues[2]
            name = "S${season.padStart(2,'0')}E${ep.padStart(2,'0')}"
            episode_number = ep.toFloatOrNull() ?: 0F
        } else {
            name = rawTitle.ifEmpty { "Episode" }
            episode_number = Regex("""\d+""").findAll(rawTitle)
                .lastOrNull()?.value?.toFloatOrNull() ?: 0F
        }
        // "19 years ago", "3 weeks ago" — just store 0, no parseable date
        date_upload = 0L
    }

    // ======================== Videos ========================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Episode page has trembed iframes inside divs like: div#options-0, div#options-1 ...
        // Each div has class "video aa-tb" and contains one <iframe>
        val videoDivs = document.select("div.video.aa-tb, div[id^='options-']")

        // Server labels come from the tab list (.aa-tbs-video li or similar)
        val serverLabels = document.select(
            ".aa-tbs-video li, ul.aa-tbs li, [class*='aa-tbs'] li",
        ).map { it.text().trim() }.filter { it.isNotBlank() }

        if (videoDivs.isNotEmpty()) {
            videoDivs.forEachIndexed { index, div ->
                val iframe = div.selectFirst("iframe") ?: return@forEachIndexed
                // Jsoup auto-decodes HTML entities: &#038; → &
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isBlank() || "about:blank" in src) return@forEachIndexed
                val label = serverLabels.getOrNull(index)?.ifBlank { "Server ${index + 1}" }
                    ?: "Server ${index + 1}"
                videoList.addAll(extractVideos(src, label))
            }
        } else {
            // Fallback: scan all iframes
            document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotBlank() && "about:blank" !in src) {
                    videoList.addAll(extractVideos(src, ""))
                }
            }
        }

        // Last resort: direct video/source tags
        if (videoList.isEmpty()) {
            document.select("video source[src]").forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank()) videoList.add(Video(src, "Direct", src))
            }
        }

        return videoList.sortedWith(
            compareByDescending<Video> {
                it.quality.contains("multi", ignoreCase = true) ||
                    it.quality.contains("hindi", ignoreCase = true)
            }.thenByDescending { it.quality },
        )
    }

    private fun extractVideos(url: String, label: String): List<Video> {
        val extractor = ToonStreamExtractor(client, headers)
        return when {
            // ToonStream native: trembed → as-cdn*.top FirePlayer
            "trembed" in url || "trid=" in url ->
                extractor.trembedVideos(url, label, baseUrl)
            "as-cdn" in url ->
                extractor.asCdnVideos(url, label)
            // External hosts
            "streamtape" in url -> extractor.streamtapeVideos(url, label)
            "dood" in url || "ds2play" in url -> extractor.doodVideos(url, label)
            "filemoon" in url || "moonplayer" in url -> extractor.filemoonVideos(url, label)
            "streamwish" in url || "swish" in url -> extractor.streamwishVideos(url, label)
            "voe.sx" in url || "voe." in url -> extractor.voeVideos(url, label)
            "upstream" in url || "uprot" in url -> extractor.upstreamVideos(url, label)
            "mp4upload" in url -> extractor.mp4uploadVideos(url, label)
            else -> extractor.genericVideos(url, label)
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
        arrayOf("All", "Action", "Adventure", "Animation", "Comedy", "Crime", "Drama",
            "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller"),
    ) {
        val slugs = arrayOf(
            Pair("All", ""), Pair("Action", "action"), Pair("Adventure", "adventure"),
            Pair("Animation", "animation"), Pair("Comedy", "comedy"), Pair("Crime", "crime"),
            Pair("Drama", "drama"), Pair("Fantasy", "fantasy"), Pair("Horror", "horror"),
            Pair("Mystery", "mystery"), Pair("Romance", "romance"), Pair("Sci-Fi", "sci-fi"),
            Pair("Thriller", "thriller"),
        )
    }

    class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        arrayOf("All", "Anime Series", "Cartoon"),
    ) {
        val slugs = arrayOf(
            Pair("All", ""), Pair("Anime Series", "anime-series"), Pair("Cartoon", "cartoon"),
        )
    }
}
