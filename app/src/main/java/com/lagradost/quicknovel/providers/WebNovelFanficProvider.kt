package com.lagradost.quicknovel.providers

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

class WebNovelFanficProvider : MainAPI() {
    override val name = "WebNovel Fanfic"
    override val mainUrl = "https://www.webnovel.com"
    override val hasMainPage = true

    override val iconId = R.drawable.icon_webnovel

    // Comprehensive tags combining categories and popular genres
    override val tags = listOf(
        // Main Categories
        Pair("All Fanfics", "fanfic"),
        Pair("Anime & Comics", "fanfic-anime-comics"),
        Pair("Video Games", "fanfic-video-games"),
        Pair("Book & Literature", "fanfic-book-literature"),
        Pair("Movies", "fanfic-movies"),
        Pair("TV", "fanfic-tv"),
        Pair("Celebrities", "fanfic-celebrities"),
        Pair("Music & Bands", "fanfic-music-bands"),
        Pair("Theater", "fanfic-theater"),
        Pair("Others", "fanfic-others"),

        // Popular Fandoms (can be used for search)
        Pair("🔥 Isekai", "search:isekai"),
        Pair("🔥 Naruto", "search:naruto"),
        Pair("🔥 Marvel", "search:marvel"),
        Pair("🔥 One Piece", "search:onepiece"),
        Pair("🔥 Harry Potter", "search:harrypotter"),
        Pair("🔥 My Hero Academia", "search:myheroacademia"),
        Pair("🔥 Pokémon", "search:pokemon"),
        Pair("🔥 DC", "search:dc"),
        Pair("🔥 Dragon Ball", "search:dragonball"),
        Pair("🔥 Fate Series", "search:fateseries"),
        Pair("🔥 High School DxD", "search:highschooldxd"),
        Pair("🔥 Bleach", "search:bleach"),
        Pair("🔥 Jujutsu Kaisen", "search:jujutsukaisen"),
        Pair("🔥 Game of Thrones", "search:gameofthrones"),
        Pair("🔥 Demon Slayer", "search:demonslayer"),
        Pair("🔥 Attack on Titan", "search:attackontitan"),
        Pair("🔥 Black Clover", "search:blackclover"),
        Pair("🔥 Genshin Impact", "search:genshinimpact"),
        Pair("🔥 Star Wars", "search:starwars"),
        Pair("🔥 Percy Jackson", "search:percyjackson"),

        // Popular Genres/Themes
        Pair("⚔️ Action", "search:action"),
        Pair("🌟 Adventure", "search:adventure"),
        Pair("💕 Romance", "search:romance"),
        Pair("🔄 Reincarnation", "search:reincarnation"),
        Pair("📈 Weak to Strong", "search:weaktostrong"),
        Pair("💪 Overpowered", "search:overpowered"),
        Pair("🏰 Kingdom Building", "search:kingdombuilding"),
        Pair("😈 Villain", "search:villain"),
        Pair("❤️ Harem", "search:harem"),
        Pair("🚫 No Harem", "search:noharem"),
        Pair("🎭 Crossover", "search:crossover"),
        Pair("🔞 R18", "search:r18"),
        Pair("🌈 Yuri", "search:yuri"),
        Pair("👨‍❤️‍👨 Yaoi", "search:yaoi"),
        Pair("✨ System", "search:system"),
        Pair("🎮 LitRPG", "search:litrpg"),
        Pair("🧙 Magic", "search:magic"),
        Pair("⚡ Superpowers", "search:superpowers"),
    )

    override val orderBys = listOf(
        Pair("Popular", "1"),
        Pair("Recommended", "2"),
        Pair("Most Collections", "3"),
        Pair("Rating", "4"),
        Pair("Time Updated", "5"),
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val selectedTag = tag ?: "fanfic"
        val order = orderBy ?: "1"

        // Handle search-based tags (for popular fandoms/genres)
        val url = if (selectedTag.startsWith("search:")) {
            val searchTerm = selectedTag.removePrefix("search:")
            "$mainUrl/search?keywords=$searchTerm&type=fanfic&orderBy=$order&pageIndex=$page"
        } else {
            "$mainUrl/stories/$selectedTag?orderBy=$order&pageIndex=$page"
        }

        val document = app.get(url, headers = headers).document

        // Handle both category pages and search results
        val selector = if (selectedTag.startsWith("search:")) {
            ".j_list_container li, li.fl"
        } else {
            ".j_category_wrapper li, li.fl"
        }

        return HeadMainPageResponse(
            url,
            list = document.select(selector).mapNotNull { element ->
                val thumb = element.selectFirst(".g_thumb") ?: return@mapNotNull null
                val href = thumb.attr("href")
                if (href.isBlank()) return@mapNotNull null

                // Try data-original first, then src
                val imgElement = element.selectFirst(".g_thumb > img")
                val coverUrl = imgElement?.attr("data-original")?.ifBlank { null }
                    ?: imgElement?.attr("src")

                // Get rating if available
                val ratingText = element.select("strong.c_m.fs0.ff_number").firstOrNull()
                    ?.select("span.vam")?.text()
                val rating = ratingText?.toFloatOrNull()?.times(20)?.toInt() // Convert 5-star to 100 scale

                // Get latest chapter if available
                val latestChapter = element.selectFirst(".j_latest_chapter")?.text()

                SearchResponse(
                    name = thumb.attr("title").ifBlank {
                        imgElement?.attr("alt") ?: "Unknown"
                    },
                    url = fixUrlNull(href) ?: return@mapNotNull null,
                    posterUrl = coverUrl?.let {
                        when {
                            it.startsWith("http") -> it
                            it.startsWith("//") -> "https:$it"
                            else -> "https://$it"
                        }
                    },
                    rating = rating,
                    latestChapter = latestChapter,
                    apiName = this.name
                )
            }
        )
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url, headers = headers).document

        // Remove comment elements
        document.select(".para-comment").remove()

        val title = document.selectFirst(".cha-tit")?.html() ?: ""
        val content = document.selectFirst(".cha-words")?.html() ?: return null

        return title + content
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keywords=${query.replace(" ", "+")}&type=fanfic"
        val document = app.get(searchUrl, headers = headers).document

        return document.select(".j_list_container li, li.fl").mapNotNull { element ->
            val thumb = element.selectFirst(".g_thumb") ?: return@mapNotNull null
            val href = thumb.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val imgElement = element.selectFirst(".g_thumb > img")
            val coverUrl = imgElement?.attr("src")?.ifBlank { null }
                ?: imgElement?.attr("data-original")

            // Get rating if available
            val ratingText = element.select("strong.c_m.fs0.ff_number").firstOrNull()
                ?.select("span.vam")?.text()
            val rating = ratingText?.toFloatOrNull()?.times(20)?.toInt()

            // Get chapter count
            val chapterText = element.select("strong.c_m.fs0.ff_number").getOrNull(1)
                ?.select("span.vam")?.text()

            newSearchResponse(
                thumb.attr("title").ifBlank {
                    imgElement?.attr("alt") ?: "Unknown"
                },
                fixUrlNull(href) ?: return@mapNotNull null
            ) {
                posterUrl = coverUrl?.let {
                    when {
                        it.startsWith("http") -> it
                        it.startsWith("//") -> "https:$it"
                        else -> "https://$it"
                    }
                }
                this.rating = rating
                this.latestChapter = chapterText
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        // Get novel name from cover image alt or title
        val name = document.selectFirst(".g_thumb > img")?.attr("alt")
            ?: document.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("Invalid name")

        // Get novel path for catalog URL
        val novelPath = url.removePrefix(mainUrl)
        val catalogUrl = "$mainUrl$novelPath/catalog"

        val catalogDoc = app.get(catalogUrl, headers = headers).document

        // Parse chapters from catalog page
        val chapters = mutableListOf<Pair<String, String>>()

        catalogDoc.select(".volume-item").forEach { volumeElement ->
            // Extract volume name
            val volumeText = volumeElement.ownText().trim()
            val volumeMatch = Regex("""Volume\s+(\d+)""").find(volumeText)
            val volumeName = volumeMatch?.let { "Volume ${it.groupValues[1]}" } ?: ""

            volumeElement.select("li").forEach { chapterElement ->
                val chapterLink = chapterElement.selectFirst("a") ?: return@forEach
                val chapterPath = chapterLink.attr("href")
                if (chapterPath.isBlank()) return@forEach

                val chapterTitle = chapterLink.attr("title").ifBlank { chapterLink.text() }.trim()
                val isLocked = chapterElement.select("svg").isNotEmpty()

                val displayName = buildString {
                    if (volumeName.isNotBlank()) append("$volumeName: ")
                    append(chapterTitle)
                    if (isLocked) append(" 🔒")
                }

                val fullUrl = fixUrlNull(chapterPath) ?: return@forEach
                chapters.add(displayName to fullUrl)
            }
        }

        val data = chapters.map { (cName, cUrl) ->
            newChapterData(cName, cUrl)
        }

        // Get cover URL
        val coverUrl = document.selectFirst(".g_thumb > img")?.attr("src")

        // Get author - find "Author:" label and get next sibling element
        val author = document.select(".det-info .c_s, .det-info span").firstOrNull { elem ->
            elem.text().trim() == "Author:"
        }?.nextElementSibling()?.text()?.trim()

        // Get status - find svg with title="Status" and get next sibling text
        val statusText = document.select(".det-hd-detail svg").firstOrNull { elem ->
            elem.attr("title") == "Status"
        }?.nextElementSibling()?.text()?.trim()

        // Get genres - multiple methods
        val genresList = mutableSetOf<String>()

        // Method 1: From tag title attribute
        document.selectFirst(".det-hd-detail > .det-hd-tag")?.attr("title")
            ?.split(",")?.forEach { genresList.add(it.trim()) }

        // Method 2: From tag links in story
        document.select("a[href*='/tags/']").forEach { tagLink ->
            val tagText = tagLink.text().trim().removePrefix("#").uppercase()
            if (tagText.isNotBlank()) genresList.add(tagText)
        }

        // Get rating
        val ratingText = document.select(".score strong").text()
        val rating = ratingText.toFloatOrNull()?.times(20)?.toInt()

        // Get synopsis
        val synopsis = document.selectFirst(".j_synopsis > p, .synopsis p")?.apply {
            select("br").before("\\n")
            select("br").remove()
        }?.text()?.replace("\\n", "\n")?.trim()

        return newStreamResponse(name, url, data) {
            this.tags = genresList.filter { it.isNotBlank() }.toList()
            this.author = author
            this.posterUrl = coverUrl?.let {
                when {
                    it.startsWith("http") -> it
                    it.startsWith("//") -> "https:$it"
                    else -> "https://$it"
                }
            }
            this.synopsis = synopsis
            this.rating = rating
            setStatus(statusText)
        }
    }
}