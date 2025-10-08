package eu.kanade.tachiyomi.extension.vi.vcomycs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SearchResponseDto(
    val data: List<SearchEntryDto>,
    val success: Boolean,
)

@Serializable
data class SearchEntryDto(
    val cstatus: String,
    val img: String,
    val isocm: Int,
    val link: String,
    val star: Float,
    val title: String,
    @SerialName("vote") val voteRaw: JsonElement, // Có thể là String hoặc Int
) {
    val vote: String
        get() = when (voteRaw) {
            is JsonPrimitive -> voteRaw.jsonPrimitive.content
            else -> "0"
        }
}

@Serializable
data class EncData(
    val ciphertext: String,
    val salt: String,
    val iv: String,
)
