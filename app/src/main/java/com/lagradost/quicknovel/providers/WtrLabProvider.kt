package com.lagradost.quicknovel.providers

import android.graphics.ColorSpace.match
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.toRate
import com.lagradost.quicknovel.util.AppUtils.parseJson
import android.util.Logimport javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


class WtrLabProvider : MainAPI() {
    override val name = "WTR-LAB"
    override val mainUrl = "https://wtr-lab.com"
    override val lang = "en"
    override val hasMainPage = true
    override val hasReviews = false
    override val usesCloudFlareKiller = false

    override val iconId = R.drawable.icon_wtrlab

    companion object {
        private const val TAG = "WtrLabProvider"
        private const val CHAPTERS_PER_REQUEST = 500L
    }

    override val tags = listOf(
        Pair("All", ""),
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Fantasy", "fantasy"),
        Pair("Harem", "harem"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Mature", "mature"),
        Pair("Mecha", "mecha"),
        Pair("Mystery", "mystery"),
        Pair("Psychological", "psychological"),
        Pair("Romance", "romance"),
        Pair("School Life", "school-life"),
        Pair("Sci-fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Supernatural", "supernatural"),
        Pair("Tragedy", "tragedy"),
        Pair("Wuxia", "wuxia"),
        Pair("Xianxia", "xianxia"),
        Pair("Xuanhuan", "xuanhuan"),
    )

    override val orderBys = listOf(
        Pair("Popular", "popular"),
        Pair("Latest", "latest"),
        Pair("Rating", "rating"),
        Pair("Views", "views"),
        Pair("New", "new"),
    )

    /**
     * Fix image URL - handle relative paths and missing protocols
     */
    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val tagPath = if (!tag.isNullOrBlank()) "/tag/$tag" else ""
        val order = orderBy ?: "popular"
        val url = "$mainUrl/en/novel-list$tagPath?sort=$order&page=$page"

        Log.d(TAG, "Loading main page: $url")
        val doc = app.get(url).document

        val novels = doc.select(".series-list > div > div > .serie-item").mapNotNull { element ->
            val titleWrap = element.selectFirst(".title-wrap") ?: return@mapNotNull null
            val titleHolder = titleWrap.selectFirst("a.title") ?: return@mapNotNull null
            val href = titleHolder.attr("href")
            if (href.isBlank()) return@mapNotNull null

            // Remove raw title element to get clean title
            titleHolder.selectFirst(".rawtitle")?.remove()
            val name = titleHolder.text().trim()
            if (name.isBlank()) return@mapNotNull null

            // Try multiple selectors for image
            val imgElement = element.selectFirst(".img-wrap img")
                ?: element.selectFirst(".image-wrap img")
                ?: element.selectFirst("a img")
                ?: element.selectFirst("img")

            // Try different attributes for image URL
            val rawPosterUrl = imgElement?.attr("data-src")
                ?: imgElement?.attr("src")
                ?: imgElement?.attr("data-lazy-src")

            val posterUrl = fixImageUrl(rawPosterUrl)

            Log.d(TAG, "Novel: $name, Image: $posterUrl")

            // Try to get rating
            val ratingText = element.selectFirst(".rating-text")?.text()
            val rating = ratingText?.toFloatOrNull()?.times(20)?.toInt()

            // Try to get latest chapter info
            val latestChapter = element.selectFirst(".chapter-info")?.text()

            val fixedUrl = fixUrlNull(href) ?: return@mapNotNull null

            SearchResponse(
                name = name,
                url = if (fixedUrl.startsWith("http")) fixedUrl else "$mainUrl$fixedUrl",
                posterUrl = posterUrl,
                rating = rating,
                latestChapter = latestChapter,
                apiName = this.name
            )
        }

        Log.d(TAG, "Found ${novels.size} novels on main page")
        return HeadMainPageResponse(url, novels)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/novel-finder?text=${query.replace(" ", "+")}"
        Log.d(TAG, "Searching: $url")
        val doc = app.get(url).document

        return doc.select(".series-list > div > div > .serie-item").mapNotNull { element ->
            val titleWrap = element.selectFirst(".title-wrap") ?: return@mapNotNull null
            val titleHolder = titleWrap.selectFirst("a.title") ?: return@mapNotNull null
            val href = titleHolder.attr("href")
            if (href.isBlank()) return@mapNotNull null

            // Remove raw title element to get clean title
            titleHolder.selectFirst(".rawtitle")?.remove()

            val name = titleHolder.text() ?: return@mapNotNull null
            newSearchResponse(name, href) {
                posterUrl = fixUrlNull(select.selectFirst("a img")?.attr("src"))
            }
        }
    }

    private suspend fun getChapterRange(
        url: String,
        rawId: Long,
        start: Long,
        end: Long
    ): List<ChapterData> {
        val chapterDataUrl =
            "$mainUrl/api/chapters/${chaptersJson.props.pageProps.serie.serieData.rawId}?start=$start&end=$end"
        val chaptersDataJson =
            app.get(chapterDataUrl).text
        val chaptersData = parseJson<ResultChaptersJsonResponse.Root>(chaptersDataJson)

            chaptersData.chapters.map { chapter ->
                // Build chapter URL - use the novel URL + /chapter-{order}
                val chapterUrl = "${url.trimEnd('/')}/chapter-${chapter.order}"
                Log.d(TAG, "Chapter ${chapter.order}: $chapterUrl")

                newChapterData(
                    name = "#${chapter.order} ${chapter.title}",
                    url = chapterUrl
                ) {
                    dateOfRelease = chapter.updatedAt
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching chapters: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Loading novel: $url")
        val doc = app.get(url).document

        // Get title - try multiple selectors
        val title = doc.selectFirst(".title-wrap .text-uppercase")?.text()?.trim()
            ?: doc.selectFirst("h1.title")?.text()?.trim()
            ?: doc.selectFirst(".novel-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Could not find title")

        Log.d(TAG, "Novel title: $title")

        // Parse JSON data from Next.js
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
        val json = jsonNode?.data() ?: throw ErrorLoadingException("no chapters")
        val chaptersJson = parseJson<ResultJsonResponse.Root>(json)


        val chapters = mutableListOf<ChapterData>()

        if (totalChapters <= CHAPTERS_PER_REQUEST) {
            chapters.addAll(getChapterRange(url, serieData.rawId, 1, totalChapters))
        } else {
            var start = 1L
            while (start <= totalChapters) {
                val end = minOf(start + CHAPTERS_PER_REQUEST - 1, totalChapters)
                chapters.addAll(getChapterRange(url, serieData.rawId, start, end))
                start = end + 1
            }
        }

        Log.d(TAG, "Total chapters loaded: ${chapters.size}")

        // Extract poster - try multiple selectors and attributes
        val imgElement = doc.selectFirst(".image-wrap img")
            ?: doc.selectFirst(".novel-cover img")
            ?: doc.selectFirst(".cover img")
            ?: doc.selectFirst("img.cover")

        val rawPosterUrl = imgElement?.attr("data-src")
            ?: imgElement?.attr("src")
            ?: imgElement?.attr("data-lazy-src")

        val posterUrl = fixImageUrl(rawPosterUrl)
        Log.d(TAG, "Poster URL: $posterUrl")

        // Extract metadata
        val synopsis = doc.selectFirst(".desc-wrap")?.text()?.trim()
            ?: doc.selectFirst(".description")?.text()?.trim()
            ?: doc.selectFirst(".synopsis")?.text()?.trim()

        val rating = doc.selectFirst(".rating-text")?.text()?.toRate(5)

        // Extract author
        val author = doc.select(".detail-line, .info-line, .meta-item").find { line ->
            line.text().contains("Author", ignoreCase = true)
        }?.selectFirst("a, span:last-child")?.text()?.trim()

        // Extract views
        val views = doc.select(".detail-line, .info-line, .meta-item").find { line ->
            line.text().contains("Views", ignoreCase = true)
        }?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // Extract status
        val statusText = doc.select(".detail-line, .info-line, .meta-item").find { line ->
            line.text().contains("Status", ignoreCase = true)
        }?.selectFirst("span, a")?.text()?.trim()

        // Extract genres/tags
        val genres = doc.select(".genre-wrap a, .tag-wrap a, .genres a, .tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return newStreamResponse(title, url, chapters) {
            synopsis = doc.selectFirst(".desc-wrap")?.text()
            posterUrl = fixUrlNull(doc.selectFirst(".image-wrap > img")?.attr("src"))
            views =
                doc.select(".detail-line").find { it.text().contains("Views") }?.text()?.split(" ")
                    ?.getOrNull(0)?.toIntOrNull()
            // author = doc.select(".author-wrap>a").text()
            rating = doc.selectFirst(".rating-text")?.text()?.toRate(5)
        }
    }

    override suspend fun loadHtml(url: String): String {
        val doc = app.get(url).document
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
        val json = jsonNode?.data() ?: throw ErrorLoadingException("no chapters")
        val chaptersJson = parseJson<LoadJsonResponse.Root>(json)
        val text = StringBuilder()
        val chapter = chaptersJson.props.pageProps.serie

        val root = app.post(
            "$mainUrl/api/reader/get", data = mapOf(
                "chapter_id" to chapter.chapter.id.toString(),
                "chapter_no" to chapter.serieData.slug.toString(),
                "force_retry" to "false",
                "language" to "en",
                "raw_id" to chapter.serieData.rawId.toString(),
                "retry" to "false",
                "translate" to "web", // translate=ai just returns a job and I am too lazy to fix that
                )
        ).parsed<LoadJsonResponse2.Root>()
        val paragraphs = decryptContent(root.data.data.body)

        for (p in paragraphs) {
            text.append("<p>")
            text.append(p)
            text.append("</p>")
        }

        /*for (select in doc.select(".chapter-body>p")) {
            if (select.ownText().contains("window._taboola")) {
                select.remove()
            }
        }*/

        return text.toString()//doc.selectFirst(".chapter-body")?.html()
    }

    fun decryptContent(encryptedText: String): List<String> {
        if (encryptedText.isEmpty()) return emptyList()

        var isArray = false
        var rawText = encryptedText

        if (encryptedText.startsWith("arr:")) {
            isArray = true
            rawText = encryptedText.removePrefix("arr:")
        } else if (encryptedText.startsWith("str:")) {
            rawText = encryptedText.removePrefix("str:")
        }

        val parts = rawText.split(":")
        if (parts.size != 3) throw IllegalArgumentException("Invalid format")

        val ivBytes = Base64.decode(parts[0], Base64.DEFAULT)
        val shortCipher = Base64.decode(parts[1], Base64.DEFAULT)
        val longCipher = Base64.decode(parts[2], Base64.DEFAULT)

        val cipherBytes = ByteArray(longCipher.size + shortCipher.size)
        System.arraycopy(longCipher, 0, cipherBytes, 0, longCipher.size)
        System.arraycopy(shortCipher, 0, cipherBytes, longCipher.size, shortCipher.size)

        val keyString = "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"
        val keyBytes = keyString.substring(0, 32).toByteArray(Charsets.UTF_8)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(cipherBytes)
        val decryptedText = decryptedBytes.toString(Charsets.UTF_8)

        return if (isArray) {
            parseJson<List<String>>(decryptedText)
        } else {
            listOf(decryptedText)
        }
    }


}

object ResultChaptersJsonResponse {
    data class Root(
        val chapters: List<Chapter>,
    )
    data class Chapter(
        @JsonProperty("serie_id")
        val serieId: Long,
        val id: Long,
        val order: Long,
        val title: String,
        val name: String,
        @JsonProperty("updated_at")
        val updatedAt: String,
    )
}

object ResultJsonResponse {
    data class Root(
        val props: Props,
        /*val page: String,
        val query: Query,
        val buildId: String,
        val isFallback: Boolean,
        val isExperimentalCompile: Boolean,
        val gssp: Boolean,
        val locale: String,
        val locales: List<String>,
        val defaultLocale: String,
        val scriptLoader: List<Any?>,*/
    )

    data class Props(
        val pageProps: PageProps,
        // @JsonProperty("__N_SSP")
        /// val nSsp: Boolean,
    )

    data class PageProps(
        val serie: Serie,
        /*val tags: List<Tag>,
        @JsonProperty("server_time")
        val serverTime: String,
        @JsonProperty("disabe_ads")
        val disabeAds: Boolean,
        @JsonProperty("_sentryTraceData")
        val sentryTraceData: String,
        @JsonProperty("_sentryBaggage")
        val sentryBaggage: String,*/
    )

    data class Serie(
        @JsonProperty("serie_data")
        val serieData: SerieData,
        /*val ranks: Ranks,
        val recommendation: List<Recommendation>,
        val raws: List<Raw3>,
        val names: List<Name>,
        @JsonProperty("other_series")
        val otherSeries: List<Series>,
        @JsonProperty("last_chapters")
        val lastChapters: List<LastChapter>,*/
        /*@JsonProperty("raw_rank")
        val rawRank: Any?,
        @JsonProperty("released_user")
        val releasedUser: Any?,*/
    )

    data class SerieData(
        @JsonProperty("raw_id")
        val rawId: Long,
        /*
        val id: Long,val slug: String,
        @JsonProperty("search_text")
        val searchText: String,
        val status: Long,
        val data: Data,
        @JsonProperty("created_at")
        val createdAt: String,
        @JsonProperty("updated_at")
        val updatedAt: String,
        val view: Long,
        @JsonProperty("in_library")
        val inLibrary: Long,
        val rating: Any?,
        @JsonProperty("chapter_count")
        val chapterCount: Long,
        val power: Long,
        @JsonProperty("total_rate")
        val totalRate: Long,
        @JsonProperty("user_status")
        val userStatus: Long,
        val verified: Boolean,
        val from: String,
        val author: String,
        @JsonProperty("ai_enabled")
        val aiEnabled: Boolean,
        @JsonProperty("released_by")
        val releasedBy: Any?,
        @JsonProperty("raw_status")
        val rawStatus: Long,*/
        @JsonProperty("raw_chapter_count")
        val rawChapterCount: Long,
        /*
        val genres: List<Long>,
        @JsonProperty("raw_verified")
        val rawVerified: Boolean,
        @JsonProperty("requested_by")
        val requestedBy: String,
        @JsonProperty("requested_by_name")
        val requestedByName: String,
        @JsonProperty("requested_member")
        val requestedMember: String,
        @JsonProperty("requested_role")
        val requestedRole: Long,*/
    )

    data class Data(
        val title: String,
        val author: String,
        val description: String,
        @JsonProperty("from_user")
        val fromUser: String?,
        val raw: Raw,
        val image: String,
    )

    data class Raw(
        val title: String,
        val author: String,
        val description: String,
    )

    /*data class Ranks(
        val week: Any?,
        val month: Any?,
        val all: String,
    )

    data class Recommendation(
        @JsonProperty("serie_id")
        val serieId: Long,
        @JsonProperty("recommendation_id")
        val recommendationId: Long,
        val score: Long,
        val id: Long,
        val slug: String,
        @JsonProperty("search_text")
        val searchText: String,
        val status: Long,
        val data: Data2,
        @JsonProperty("created_at")
        val createdAt: String,
        @JsonProperty("updated_at")
        val updatedAt: String,
        val view: Long,
        @JsonProperty("in_library")
        val inLibrary: Long,
        val rating: Double?,
        @JsonProperty("chapter_count")
        val chapterCount: Long,
        val power: Long,
        @JsonProperty("total_rate")
        val totalRate: Long,
        @JsonProperty("user_status")
        val userStatus: Long,
        val verified: Boolean,
        val from: String?,
        val author: String,
        @JsonProperty("raw_id")
        val rawId: Long,
        @JsonProperty("ai_enabled")
        val aiEnabled: Boolean,
    )

    data class Data2(
        val title: String,
        val author: String,
        val description: String,
        @JsonProperty("from_user")
        val fromUser: String?,
        val raw: Raw2,
        val image: String,
    )

    data class Raw2(
        val title: String,
        val author: String,
        val description: String,
    )

    data class Raw3(
        val id: Long,
        @JsonProperty("chapter_count")
        val chapterCount: Long,
        val view: Long,
        val slug: String,
        @JsonProperty("created_at")
        val createdAt: String,
        val default: Boolean,
        val verified: Boolean,
    )

    data class Name(
        val title: String,
        @JsonProperty("raw_title")
        val rawTitle: String,
    )

    data class Series(
        val id: Long,
        val slug: String,
        @JsonProperty("search_text")
        val searchText: String,
        val status: Long,
        val data: Data3,
        @JsonProperty("created_at")
        val createdAt: String,
        @JsonProperty("updated_at")
        val updatedAt: String,
        val view: Long,
        @JsonProperty("in_library")
        val inLibrary: Long,
        val rating: Double,
        @JsonProperty("chapter_count")
        val chapterCount: Long,
        val power: Long,
        @JsonProperty("total_rate")
        val totalRate: Long,
        @JsonProperty("user_status")
        val userStatus: Long,
        val verified: Boolean,
        val from: String,
        val author: String,
        @JsonProperty("raw_id")
        val rawId: Long,
    )

    data class Data3(
        val title: String,
        val author: String,
        val description: String,
        @JsonProperty("from_user")
        val fromUser: String,
        val raw: Raw4,
        val image: String,
    )

    data class Raw4(
        val title: String,
        val author: String,
        val description: String,
    )

    data class LastChapter(
        //@JsonProperty("serie_id")
        //val serieId: Long,
        //val id: Long,
        val order: Long,
        val title: String,
        //val name: String,
        @JsonProperty("updated_at")
        val updatedAt: String?,
    )

    data class Tag(
        val id: Long,
        val title: String,
        val slug: String,
    )

    data class Query(
        val sid: String,
        @JsonProperty("serie_slug")
        val serieSlug: String,
    )*/
}

object LoadJsonResponse2 {

    data class Root(
        // val success: Boolean,
        // val chapter: Chapter,
        val data: Data,
    )

    data class Chapter(
        val id: Long,
        @JsonProperty("raw_id")
        val rawId: Long,
        val order: Long,
        val title: String,
    )

    data class Data(
        /*@JsonProperty("raw_id")
        val rawId: Long,
        @JsonProperty("chapter_id")
        val chapterId: Long,
        val status: Long,
        */
        val data: Data2,
        /*@JsonProperty("created_at")
        val createdAt: String,
        val language: String,*/
    )

    data class Data2(
        val body: String = "",
        /*val hans: String,
        val hash: String,
        val model: String,
        val patch: Any?,
        val title: String,
        val prompt: String,
        @JsonProperty("glossory_hash")
        val glossoryHash: String,
        @JsonProperty("glossary_build")
        val glossaryBuild: Long,*/
    )
    data class Terms(
        val terms: List<List<String>>,
    )
}

object LoadJsonResponse {
    data class Root(
        val props: Props,
        val page: String,
        val query: Query,
        val buildId: String,
        val isFallback: Boolean,
        val isExperimentalCompile: Boolean,
        val gssp: Boolean,/*
        val locale: String,
        val locales: List<String>,
        val defaultLocale: String,
        val scriptLoader: List<Any?>,*/
    )

    data class Props(
        val pageProps: PageProps,
        /*@JsonProperty("__N_SSP")
        val nSsp: Boolean,
        */
    )

    data class PageProps(
        val serie: Serie,
        /*@JsonProperty("disabe_ads")
        val disabeAds: Boolean,
        @JsonProperty("server_time")
        val serverTime: String,
        @JsonProperty("active_service")
        val activeService: ActiveService,
        @JsonProperty("_sentryTraceData")
        val sentryTraceData: String,
        @JsonProperty("_sentryBaggage")
        val sentryBaggage: String,*/
    )

    data class Chapter(
        val id: Long,
        val slug: String?,
        @JsonProperty("raw_id")
        val rawId: Long,
        /*@JsonProperty("serie_id")
        val serieId: Long,
        val status: Long,
        val slug: String,
        val name: String,
        val order: Long,
        @JsonProperty("is_update")
        val isUpdate: Boolean,
        @JsonProperty("created_at")
        val createdAt: String,
        @JsonProperty("updated_at")
        val updatedAt: String,
        val title: String,
        val code: String,*/
    )
    data class Serie(
        @JsonProperty("serie_data")
        val serieData: SerieData,
        /*
        @JsonProperty("default_service")
        val defaultService: String,*/
        val chapter: Chapter,
    )

    data class SerieData(
        val id: Long,
        val slug: String,
        val data: Data,
        @JsonProperty("raw_id")
        val rawId: Long,
        @JsonProperty("user_status")
        val userStatus: Long,
        @JsonProperty("is_default")
        val isDefault: Boolean,
        @JsonProperty("chapter_count")
        val chapterCount: Long,
        @JsonProperty("ai_enabled")
        val aiEnabled: Boolean,
        @JsonProperty("raw_status")
        val rawStatus: Long,
    )

    data class Data(
        val title: String,
        val author: String,
        val description: String,
        @JsonProperty("from_user")
        val fromUser: String,
        val raw: Raw,
        val image: String,
    )

    data class Raw(
        val title: String,
        val author: String,
        val description: String,
    )



    data class ActiveService(
        val id: String,
        val label: String,
    )

    data class Query(
        val locale: String,
        @JsonProperty("serie_slug")
        val serieSlug: String,
        @JsonProperty("chapter_no")
        val chapterNo: String,
    )

}