package com.github.andreyasadchy.xtra.model.gql.game

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class GameClipsResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val game: Game,
    )

    @Serializable
    class Game(
        val clips: Clips,
    )

    @Serializable
    class Clips(
        val edges: List<Item>,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: Clip,
        val cursor: String? = null,
    )

    @Serializable
    class Clip(
        val slug: String? = null,
        val broadcaster: User? = null,
        val title: String? = null,
        val viewCount: Int? = null,
        val createdAt: String? = null,
        val thumbnailURL: String? = null,
        val durationSeconds: Double? = null,
    )

    @Serializable
    class User(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
        val profileImageURL: String? = null,
    )
}