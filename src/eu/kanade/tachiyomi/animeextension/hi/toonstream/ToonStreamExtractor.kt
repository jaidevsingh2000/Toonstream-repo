package eu.kanade.tachiyomi.animeextension.hi.toonstream

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class ToonStreamExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    fun streamtapeVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val videoUrl = Regex("""videolink.*?=.*?'(//streamtape[^']+)'""")
                .find(body)?.groupValues?.get(1)?.let { "https:$it" }
                ?: Regex("""'(https://streamtape\.[^/]+/get_video[^']+)'""").find(body)?.groupValues?.get(1)
                ?: return emptyList()
            listOf(Video(videoUrl, "Streamtape [$langHint]".trim(), videoUrl, headers))
        } catch (_: Exception) { emptyList() }
    }

    fun doodVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val normalUrl = url.replace("/d/", "/e/").replace("/f/", "/e/")
            val body = client.newCall(Request.Builder().url(normalUrl).headers(headers).build()).execute().body.string()
            val md5 = Regex("""\$\.(md5|token)\s*=\s*'([^']+)'""").find(body)?.groupValues?.get(2) ?: return emptyList()
            val baseUrl = Regex("""'(/pass_md5/[^']+)'""").find(body)?.groupValues?.get(1) ?: return emptyList()
            val videoBase = client.newCall(Request.Builder().url("${normalUrl.substringBefore("/e/")}$baseUrl").headers(headers).addHeader("Referer", normalUrl).build()).execute().body.string()
            val videoUrl = "$videoBase$md5?token=$md5&expiry=${System.currentTimeMillis()}"
            listOf(Video(videoUrl, "DoodStream [$langHint]".trim(), videoUrl, headers))
        } catch (_: Exception) { emptyList() }
    }

    fun filemoonVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val body = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().body.string()
            val packed = Regex("""eval\(function\(p,a,c,k,e,[rd]""").find(body) ?: return emptyList()
            val unpacked = unpackJs(body.substring(packed.range.first).substringBefore("</script>"))
            val m3u8 = Regex("""sources:\s*\[\{file:"([^"]+\.m3u8[^"]*)""").find(unpacked)?.groupValues?.get(1) ?: return emptyList()
            listOf(Video(m3u8, "Filemoon [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    fun streamwishVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val body = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().body.string()
            val packed = Regex("""eval\(function\(p,a,c,k,e,[rd]""").find(body)
            val sourceBody = if (packed != null) unpackJs(body.substring(packed.range.first).substringBefore("</script>")) else body
            val m3u8 = Regex("""file:"([^"]+\.m3u8[^"]*)"""").find(sourceBody)?.groupValues?.get(1) ?: return emptyList()
            listOf(Video(m3u8, "StreamWish [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    fun voeVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val body = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().body.string()
            val m3u8 = Regex(""""hls"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return emptyList()
            listOf(Video(m3u8, "Voe [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    fun upstreamVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val body = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().body.string()
            val m3u8 = Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(body)?.groupValues?.get(1) ?: return emptyList()
            listOf(Video(m3u8, "Upstream [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    fun mp4uploadVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val body = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().body.string()
            val mp4 = Regex("""src:\s*"(https://[^"]+\.mp4)"""").find(body)?.groupValues?.get(1) ?: return emptyList()
            listOf(Video(mp4, "MP4Upload [$langHint]".trim(), mp4, headers))
        } catch (_: Exception) { emptyList() }
    }

    fun genericVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val body = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().body.string()
            val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(body)?.groupValues?.get(1)
            if (m3u8 != null) return listOf(Video(m3u8, "Direct HLS [$langHint]".trim(), m3u8, headers))
            val mp4 = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(body)?.groupValues?.get(1)
            if (mp4 != null) return listOf(Video(mp4, "Direct MP4 [$langHint]".trim(), mp4, headers))
            emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun unpackJs(packed: String): String {
        return try {
            val p = Regex("""\('([^']+)',(\d+),(\d+),'([^']+)'""").find(packed) ?: return packed
            val (_, payload, radixStr, _, keys) = p.groupValues
            val radix = radixStr.toInt()
            val keyArray = keys.split("|")
            var result = payload
            for (i in keyArray.indices.reversed()) {
                val key = keyArray[i]
                if (key.isNotEmpty()) result = result.replace(Regex("\\b${i.toString(radix)}\\b"), key)
            }
            result
        } catch (_: Exception) { packed }
    }
                            }
