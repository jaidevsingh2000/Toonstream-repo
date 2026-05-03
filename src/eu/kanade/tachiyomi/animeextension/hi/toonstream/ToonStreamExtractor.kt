package eu.kanade.tachiyomi.animeextension.hi.toonstream

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ToonStreamExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    // ======================== ToonStream native player ========================
    // Flow: episode page → trembed URL → as-cdn*.top/video/HASH → POST API → m3u8

    fun trembedVideos(trembedUrl: String, label: String, siteReferer: String): List<Video> {
        return try {
            // Step 1: Follow the trembed redirect page to get the as-cdn iframe URL
            val resp = client.newCall(
                Request.Builder()
                    .url(trembedUrl)
                    .headers(headers)
                    .addHeader("Referer", siteReferer)
                    .build(),
            ).execute()
            val body = resp.body.string()

            // Step 2: Extract the as-cdn*.top/video/HASH iframe src
            val asCdnUrl = Regex(
                """<iframe[^>]+(?:src|data-src)=["'](https://as-cdn\d*\.top/(?:video|player/index\.php)[^"']+)["']""",
            ).find(body)?.groupValues?.get(1)
                ?: Regex("""["'](https://as-cdn\d*\.top/video/[a-f0-9]{30,})["']""")
                    .find(body)?.groupValues?.get(1)
                ?: return emptyList()

            asCdnVideos(asCdnUrl, label)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun asCdnVideos(url: String, label: String): List<Video> {
        return try {
            // Extract host (as-cdn21.top) and video hash
            val hash = url.substringAfter("/video/").substringBefore("?").trim()
            val host = url.substringBefore("/video/") // e.g. https://as-cdn21.top

            // POST to the FirePlayer API
            val apiUrl = "$host/player/index.php?data=$hash&do=getVideo"
            val apiResp = client.newCall(
                Request.Builder()
                    .url(apiUrl)
                    .post("".toRequestBody(null))
                    .headers(headers)
                    .addHeader("Referer", url)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .build(),
            ).execute()
            val json = apiResp.body.string()

            val videoSource = Regex(""""videoSource"\s*:\s*"([^"]+)"""")
                .find(json)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: return emptyList()

            val qualityLabel = label.ifBlank { "Hindi Dub" }
            listOf(Video(videoSource, qualityLabel, videoSource, headers))
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ======================== Streamtape ========================

    fun streamtapeVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val videoUrl = Regex("""videolink.*?=.*?'(//streamtape[^']+)'""")
                .find(body)?.groupValues?.get(1)?.let { "https:$it" }
                ?: Regex("""'(https://streamtape\.[^/]+/get_video[^']+)'""")
                    .find(body)?.groupValues?.get(1)
                ?: return emptyList()
            listOf(Video(videoUrl, "Streamtape [$langHint]".trim(), videoUrl, headers))
        } catch (_: Exception) { emptyList() }
    }

    // ======================== DoodStream ========================

    fun doodVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val normalUrl = url.replace("/d/", "/e/").replace("/f/", "/e/")
            val resp = client.newCall(Request.Builder().url(normalUrl).headers(headers).build()).execute()
            val body = resp.body.string()
            val md5 = Regex("""\$\.(md5|token)\s*=\s*'([^']+)'""").find(body)?.groupValues?.get(2)
                ?: return emptyList()
            val baseUrl = Regex("""'(/pass_md5/[^']+)'""").find(body)?.groupValues?.get(1)
                ?: return emptyList()
            val host = normalUrl.substringBefore("/e/")
            val passUrl = "$host$baseUrl"
            val passResp = client.newCall(
                Request.Builder().url(passUrl)
                    .headers(headers)
                    .addHeader("Referer", normalUrl)
                    .build(),
            ).execute()
            val videoBase = passResp.body.string()
            val videoUrl = "$videoBase$md5?token=$md5&expiry=${System.currentTimeMillis()}"
            listOf(Video(videoUrl, "DoodStream [$langHint]".trim(), videoUrl, headers))
        } catch (_: Exception) { emptyList() }
    }

    // ======================== Filemoon ========================

    fun filemoonVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val packed = Regex("""eval\(function\(p,a,c,k,e,[rd]""").find(body) ?: return emptyList()
            val packedStr = body.substring(packed.range.first).substringBefore("</script>")
            val unpacked = unpackJs(packedStr)
            val m3u8 = Regex("""sources:\s*\[\{file:"([^"]+\.m3u8[^"]*)"""")
                .find(unpacked)?.groupValues?.get(1) ?: return emptyList()
            listOf(Video(m3u8, "Filemoon [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    // ======================== StreamWish ========================

    fun streamwishVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val packed = Regex("""eval\(function\(p,a,c,k,e,[rd]""").find(body)
            val sourceBody = if (packed != null) {
                val packedStr = body.substring(packed.range.first).substringBefore("</script>")
                unpackJs(packedStr)
            } else body
            val m3u8 = Regex("""file:"([^"]+\.m3u8[^"]*)"""").find(sourceBody)?.groupValues?.get(1)
                ?: return emptyList()
            listOf(Video(m3u8, "StreamWish [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    // ======================== Voe.sx ========================

    fun voeVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val m3u8 = Regex(""""hls"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                ?: return emptyList()
            listOf(Video(m3u8, "Voe [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    // ======================== Upstream ========================

    fun upstreamVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val m3u8 = Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(body)?.groupValues?.get(1)
                ?: return emptyList()
            listOf(Video(m3u8, "Upstream [$langHint]".trim(), m3u8, headers))
        } catch (_: Exception) { emptyList() }
    }

    // ======================== MP4Upload ========================

    fun mp4uploadVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val mp4 = Regex("""src:\s*"(https://[^"]+\.mp4)"""").find(body)?.groupValues?.get(1)
                ?: return emptyList()
            listOf(Video(mp4, "MP4Upload [$langHint]".trim(), mp4, headers))
        } catch (_: Exception) { emptyList() }
    }

    // ======================== Generic fallback ========================

    fun genericVideos(url: String, langHint: String = ""): List<Video> {
        return try {
            val resp = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            val body = resp.body.string()
            val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(body)?.groupValues?.get(1)
            if (m3u8 != null) return listOf(Video(m3u8, "HLS [$langHint]".trim(), m3u8, headers))
            val mp4 = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(body)?.groupValues?.get(1)
            if (mp4 != null) return listOf(Video(mp4, "MP4 [$langHint]".trim(), mp4, headers))
            emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ======================== JS Unpacker ========================

    private fun unpackJs(packed: String): String {
        return try {
            val p = Regex("""\('([^']+)',(\d+),(\d+),'([^']+)'""").find(packed) ?: return packed
            val (_, payload, radixStr, _, keys) = p.groupValues
            val radix = radixStr.toInt()
            val keyArray = keys.split("|")
            var result = payload
            for (i in keyArray.indices.reversed()) {
                val key = keyArray[i]
                if (key.isNotEmpty()) {
                    result = result.replace(Regex("\\b${i.toString(radix)}\\b"), key)
                }
            }
            result
        } catch (_: Exception) { packed }
    }
}
