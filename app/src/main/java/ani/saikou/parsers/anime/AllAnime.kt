package ani.saikou.parsers.anime

import android.net.Uri
import ani.saikou.*
import ani.saikou.anilist.Anilist
import ani.saikou.parsers.*
import ani.saikou.parsers.anime.extractors.FPlayer
import ani.saikou.parsers.anime.extractors.GogoCDN
import ani.saikou.parsers.anime.extractors.StreamSB
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DecimalFormat

class AllAnime : AnimeParser() {
    override val name = "AllAnime"
    override val saveName = "all_anime"
    override val hostUrl = "https://allanime.site"
    override val isDubAvailableSeparately = true

    private val apiHost = "https://blog.allanimenews.com/"
    private val idRegex = Regex("${hostUrl}/anime/(\\w+)")
    private val epNumRegex = Regex("/[sd]ub/(\\d+)")


    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        val responseArray = mutableListOf<Episode>()
        tryWithSuspend {
            val showId = idRegex.find(animeLink)?.groupValues?.get(1)
            if (showId != null) {
                val episodeInfos = getEpisodeInfos(showId)
                val format = DecimalFormat("#####.#####")
                episodeInfos?.sortedBy { it.episodeIdNum }?.forEach { epInfo ->
                    val link = """${hostUrl}/anime/$showId/episodes/${if (selectDub) "dub" else "sub"}/${epInfo.episodeIdNum}"""
                    val epNum = format.format(epInfo.episodeIdNum).toString()
                    val thumbnail = epInfo.thumbnails?.let { if (it.isNotEmpty()) FileUrl(it[0]) else null }
                    responseArray.add(Episode(epNum, link = link, epInfo.notes, thumbnail))
                }

            }
        }
        return responseArray
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Any?): List<VideoServer> {
        val showId = idRegex.find(episodeLink)?.groupValues?.get(1)
        val videoServers = mutableListOf<VideoServer>()
        val episodeNum = epNumRegex.find(episodeLink)?.groupValues?.get(1)
        if (showId != null && episodeNum != null) {
            tryWithSuspend {
                val variables =
                    """{"showId":"$showId","translationType":"${if (selectDub) "dub" else "sub"}","episodeString":"$episodeNum"}"""
                graphqlQuery(
                    variables,
                    "29f49ce1a69320b2ab11a475fd114e5c07b03a7dc683f77dd502ca42b26df232"
                )?.data?.episode?.sourceUrls?.forEach { source ->
                    // It can be that two different actual sources share the same sourceName
                    var serverName = source.sourceName
                    var sourceNum = 2
                    // Sometimes provides relative links just because ¯\_(ツ)_/¯
                    while (videoServers.any { it.name == serverName }) {
                        serverName = "${source.sourceName} ($sourceNum)"
                        sourceNum++
                    }

                    if (source.sourceUrl.toHttpUrlOrNull() == null) {
                        val jsonUrl = """${apiHost}${source.sourceUrl.replace("clock", "clock.json").substring(1)}"""
                        videoServers.add(VideoServer(serverName, jsonUrl))
                    } else {
                        videoServers.add(VideoServer(serverName, source.sourceUrl))
                    }
                }
            }
        }
        return videoServers
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? {
        val serverUrl = Uri.parse(server.embed.url)
        val domain = serverUrl.host ?: return null
        val path = serverUrl.path ?: return null
        val extractor: VideoExtractor? = when {
            "gogo" in domain    -> GogoCDN(server)
            "goload" in domain  -> GogoCDN(server)
            "sb" in domain      -> StreamSB(server)
            "fplayer" in domain -> FPlayer(server)
            "fembed" in domain  -> FPlayer(server)
            "apivtwo" in path   -> AllAnimeExtractor(server)
            else                -> null
        }
        return extractor
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val responseArray = arrayListOf<ShowResponse>()
        tryWithSuspend {
            val variables =
                """{"search":{"allowAdult":${Anilist.adult},"query":"$query"},"translationType":"${if (selectDub) "dub" else "sub"}"}"""
            val edges =
                graphqlQuery(variables, "9343797cc3d9e3f444e2d3b7db9a84d759b816a4d84512ea72d079f85bb96e98")?.data?.shows?.edges
            if (!edges.isNullOrEmpty()) {
                for (show in edges) {
                    val link = """${hostUrl}/anime/${show.id}"""
                    val otherNames = mutableListOf<String>()
                    show.englishName?.let { otherNames.add(it) }
                    show.nativeName?.let { otherNames.add(it) }
                    show.altNames?.forEach { otherNames.add(it) }
                    if (show.thumbnail == null) {
                        toastString(""""Could not get thumbnail for ${show.id}""")
                        continue
                    }
                    responseArray.add(
                        ShowResponse(
                            show.name,
                            link,
                            show.thumbnail,
                            otherNames,
                            show.availableEpisodes.let { if (selectDub) it.dub else it.sub })
                    )
                }

            }
        }
        return responseArray
    }

    private suspend fun graphqlQuery(variables: String, persistHash: String): Query? {
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$persistHash"}}"""
        val graphqlUrl = ("$hostUrl/graphql").toHttpUrl().newBuilder().addQueryParameter("variables", variables)
            .addQueryParameter("extensions", extensions).build()
        val headers = mutableMapOf<String, String>()
        headers["Host"] = "allanime.site"
        return tryWithSuspend {
            client.get(graphqlUrl.toString(), headers).parsed()
        }
    }

    private suspend fun getEpisodeInfos(showId: String): List<EpisodeInfo>? {
        val variables = """{"ids": ["$showId"]}"""
        val show = graphqlQuery(variables, "73492472c6af978c1ab89f7a177f8471a7cae41dadb95bcb9099d5e5caa2a8f9")?.data?.showsWithIds?.getOrNull(0)
        if (show != null) {
            val epCount = if (selectDub) show.availableEpisodes.dub else show.availableEpisodes.sub
            val epVariables = """{"showId":"$showId","episodeNumStart":0,"episodeNumEnd":${epCount}}"""
            return graphqlQuery(
                epVariables,
                "73d998d209d6d8de325db91ed8f65716dce2a1c5f4df7d304d952fa3f223c9e8"
            )?.data?.episodeInfos
        }
        return null
    }

    private class AllAnimeExtractor(override val server: VideoServer) : VideoExtractor() {
        private val languageRegex = Regex("vo_a_hls_(\\w+-\\w+)")

        override suspend fun extract(): VideoContainer {
            val url = server.embed.url
            val rawResponse = client.get(url)
            val linkList = mutableListOf<String>()
            if (rawResponse.code < 400) {
                val response = rawResponse.text
                Mapper.parse<ApiSourceResponse>(response).links.forEach {
                    // Avoid languages other than english when multiple urls are provided
                    val matchesLanguagePattern = languageRegex.find(it.resolutionStr)
                    val language = matchesLanguagePattern?.groupValues?.get(1)
                    if (matchesLanguagePattern == null || language?.contains("en") == true) {
                        (it.src ?: it.link)?.let { fileUrl ->
                            linkList.add(fileUrl)
                        }
                    }
                }
            }

            return VideoContainer(toVideoList(linkList))
        }

        private suspend fun toVideoList(
            links: List<String>
        ): List<Video> {
            val videos = mutableListOf<Video>()
            val headers = mutableMapOf<String, String>()
            links.forEach {
                val fileUrl = FileUrl(it, headers)
                val urlPath = Uri.parse(it).path
                if (urlPath != null) {
                    if (urlPath.endsWith(".m3u8")) {
                        videos.add(Video(null, true, fileUrl, getSize(fileUrl)))
                    }
                    if (urlPath.endsWith(".mp4")) {
                        if ("king.stronganime" in it) {
                            headers["Referer"] = "https://allanime.site"
                        }
                        videos.add(Video(null, false, fileUrl, getSize(fileUrl)))
                    }
                }
            }
            return videos
        }
    }

    override suspend fun loadSavedShowResponse(mediaId: Int): ShowResponse? {
        return loadData("${saveName}_$mediaId")
    }

    override fun saveShowResponse(mediaId: Int, response: ShowResponse?, selected: Boolean) {
        if (response != null) {
            setUserText("${if (selected) "Selected" else "Found"} : ${response.name}")
            saveData("${saveName}_$mediaId", response)
        }
    }

    private data class Query(@SerializedName("data") var data: Data?) {
        data class Data(
            @SerializedName("shows") val shows: ShowsConnection?,
            @SerializedName("show") val show: Show?,
            @SerializedName("showsWithIds") val showsWithIds: List<Show>?,
            @SerializedName("episodeInfos") val episodeInfos: List<EpisodeInfo>?,
            @SerializedName("episode") val episode: AllAnimeEpisode?,
        )

        data class ShowsConnection(
            @SerializedName("edges") val edges: List<Show>
        )

        data class Show(
            @SerializedName("_id") val id: String,
            @SerializedName("name") val name: String,
            @SerializedName("englishName") val englishName: String?,
            @SerializedName("nativeName") val nativeName: String?,
            @SerializedName("thumbnail") val thumbnail: String?,
            @SerializedName("availableEpisodes") val availableEpisodes: AvailableEpisodes,
            @SerializedName("altNames") val altNames: List<String>?
        )

        data class AvailableEpisodes(
            @SerializedName("sub") val sub: Int,
            @SerializedName("dub") val dub: Int,
            // @SerializedName("raw") val raw: Int,
        )

//        data class LastEpisodeInfos(
//            @SerializedName("sub") val sub: LastEpisodeInfo?,
//            @SerializedName("dub") val dub: LastEpisodeInfo?,
//        )
//
//        data class LastEpisodeInfo(
//            @SerializedName("episodeString") val episodeString: String?,
//            @SerializedName("notes") val notes: String?
//        )

        data class AllAnimeEpisode(
            @SerializedName("sourceUrls") var sourceUrls: List<SourceUrl>
        )

        data class SourceUrl(
            @SerializedName("sourceUrl") val sourceUrl: String,
            @SerializedName("sourceName") val sourceName: String,
            @SerializedName("priority") val priority: String
        )
    }

    private data class EpisodeInfo(
        // Episode "numbers" can have decimal values, hence float
        @SerializedName("episodeIdNum") val episodeIdNum: Float,
        @SerializedName("notes") val notes: String?,
        @SerializedName("thumbnails") val thumbnails: List<String>?,
        @SerializedName("vidInforssub") val vidInforssub: VidInfo?,
        @SerializedName("vidInforsdub") val vidInforsdub: VidInfo?,
    ) {
        data class VidInfo(
            // @SerializedName("vidPath") val vidPath
            @SerializedName("vidResolution") val vidResolution: Int?,
            @SerializedName("vidSize") val vidSize: Double?,
        )
    }

    private data class ApiSourceResponse(@SerializedName("links") val links: List<ApiLink>) {
        data class ApiLink(
            @SerializedName("link") val link: String?,
            @SerializedName("src") val src: String?,
            @SerializedName("hls") val hls: Boolean?,
            @SerializedName("mp4") val mp4: Boolean?,
            @SerializedName("resolutionStr") val resolutionStr: String,
        )
    }

}


