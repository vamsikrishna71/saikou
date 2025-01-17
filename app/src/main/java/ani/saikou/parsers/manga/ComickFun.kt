package ani.saikou.parsers.manga

import ani.saikou.client
import ani.saikou.parsers.MangaChapter
import ani.saikou.parsers.MangaImage
import ani.saikou.parsers.MangaParser
import ani.saikou.parsers.ShowResponse
import ani.saikou.toast
import com.google.gson.annotations.SerializedName

class ComickFun : MangaParser() {

    override val name = "ComickFun"
    override val saveName = "comick_fun"
    override val hostUrl = "https://api.comick.fun"

    override suspend fun search(query: String): List<ShowResponse> {
        val resp = client.get("https://api.comick.fun/search?q=${encode(query)}&tachiyomi=true").parsed<List<SearchData>>()
        return resp.map { manga ->
            val mangaLink = "$hostUrl/comic/${manga.id}/chapter?tachiyomi=true"
            ShowResponse(name = manga.title, link = mangaLink, coverUrl = manga.cover_url, otherNames = manga.md_titles.map { it.title },
                extra = mapOf("slug" to manga.slug)) // need this slug for loadChapters
        }
    }

    override suspend fun loadChapters(mangaLink: String, extra: Map<String, String>?): List<MangaChapter> {
        // You only need "hid" from here
        val resp = client.get(mangaLink).parsed<MangaChapterData>()
        // Contains other languages too. So filter it
        val filtered = resp.chapters.filter { chapter -> chapter.lang == "en" }
        val buildManifestId = getBuildManifest()
        // Maybe useful in future if website changes/breaks
        if (buildManifestId == null) { toast("getBuildManifest() returned null") }
        val weirdUrl = "https://comick.fun/_next/data/${buildManifestId}/comic/${extra!!["slug"]}/${filtered[0].hid}-chapter-0-en.json"
        val secondResp = client.get(weirdUrl).parsed<WeirdUrlData>()
        return secondResp.pageProps.chapters.reversed().map {
            val chapterLink = "$hostUrl/chapter/${it.hid}?tachiyomi=true"
            MangaChapter(number = it.chap.toString(), link = chapterLink, title = null)
        }
    }

    override suspend fun loadImages(chapterLink: String): List<MangaImage> {
        val resp = client.get(chapterLink).parsed<MangaImageData>()
        return resp.chapter.images.map { MangaImage(url = it.url) }
    }

    companion object {

        private var lastChecked = 0L
        private var buildManifestId: String? = null

        private suspend fun getBuildManifest(): String? {
            buildManifestId = if (buildManifestId != null && (lastChecked - System.currentTimeMillis()) < 1000 * 60 * 30) buildManifestId
            else {
                lastChecked = System.currentTimeMillis()
                val document = client.get("https://comick.fun/").text
                val buildIdRe = Regex("buildId\":\"(.+?)\"")
                buildIdRe.find(document, 0)?.groupValues?.get(1)
            }
            return buildManifestId
        }

    }

    private data class WeirdUrlData(@SerializedName("pageProps") val pageProps: Data) {
        data class Data(@SerializedName("chapters") val chapters: List<Chapter>) {
            data class Chapter(
                @SerializedName("chap") val chap: String?, // chapter number
                @SerializedName("hid") val hid: String,
            )
        }
    }

    private data class SearchData(
        @SerializedName("title") val title: String,
        @SerializedName("id") val id: Int,
        @SerializedName("slug") val slug: String,
        @SerializedName("md_titles") val md_titles: List<MdTitles>, // other titles
        @SerializedName("cover_url") val cover_url: String,
    ) {
        data class MdTitles(
            @SerializedName("title") val title: String,
        )
    }

    private data class MangaChapterData(@SerializedName("chapters") val chapters: List<Chapter>) {
        data class Chapter(
            @SerializedName("chap") val chap: String?,  // chapter number
            @SerializedName("title") val title: String?,
            @SerializedName("lang") val lang: String?,  // may contain other lang too, so filter "en" using this
            @SerializedName("hid") val hid: String,
        )
    }

    private data class MangaImageData(@SerializedName("chapter") val chapter: Chapter) {
        data class Chapter(@SerializedName("images") val images: List<Image>) {
            data class Image(@SerializedName("url") val url: String)
        }
    }

}