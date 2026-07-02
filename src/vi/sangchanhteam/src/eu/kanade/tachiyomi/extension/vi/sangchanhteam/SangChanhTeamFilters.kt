package eu.kanade.tachiyomi.extension.vi.sangchanhteam

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

// Interface cho các filter có query param
interface UriPartFilter {
    val queryParam: String
    fun toUriPart(): String
}

// Multi-select checkbox cho thể loại
class Genre(name: String, val id: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Thể loại", genres)

// Select đơn
open class SelectFilter(
    name: String,
    override val queryParam: String,
    private val vals: Array<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), state), UriPartFilter {
    override fun toUriPart() = vals[state].second
}

class TypeFilter : SelectFilter(
    "Loại",
    "type",
    arrayOf(
        Pair("Tất cả loại", ""),
        Pair("Truyện tranh", "comic"),
        Pair("Tiểu thuyết", "novel"),
        Pair("Oneshot", "oneshot"),
    ),
)

class StatusFilter : SelectFilter(
    "Trạng thái",
    "status",
    arrayOf(
        Pair("Tất cả tình trạng", ""),
        Pair("Đang tiến hành", "ongoing"),
        Pair("Kết thúc mùa", "season_end"),
        Pair("Trọn bộ", "completed"),
        Pair("Nguồn tạm ngưng", "source_hiatus"),
        Pair("Đã theo kịp", "caught_up"),
        Pair("Bị hủy", "dropped"),
    ),
)

class AgeRatingFilter : SelectFilter(
    "Độ tuổi",
    "age_rating",
    arrayOf(
        Pair("Tất cả lứa tuổi", ""),
        Pair("Mọi lứa tuổi", "all"),
        Pair("13+", "13+"),
        Pair("16+", "16+"),
        Pair("18+", "18+"),
    ),
)

class TeamFilter : SelectFilter(
    "Nhóm dịch",
    "team",
    arrayOf(
        Pair("Tất cả nhóm", ""),
        Pair("Sang Chảnh Team", "10"),
    ),
)

class RatingMinFilter : SelectFilter(
    "Đánh giá tối thiểu",
    "rating_min",
    arrayOf(
        Pair("Tối thiểu", "0"),
        Pair("1★", "1"),
        Pair("2★", "2"),
        Pair("3★", "3"),
        Pair("4★", "4"),
        Pair("5★", "5"),
    ),
)

class RatingMaxFilter : SelectFilter(
    "Đánh giá tối đa",
    "rating_max",
    arrayOf(
        Pair("Tối đa", "6"),
        Pair("1★", "1"),
        Pair("2★", "2"),
        Pair("3★", "3"),
        Pair("4★", "4"),
        Pair("5★", "5"),
    ),
)

class SortFilter : SelectFilter(
    "Sắp xếp theo",
    "sort",
    arrayOf(
        Pair("Mới cập nhật", "updated"),
        Pair("Mới nhất", "new"),
        Pair("Cũ nhất", "old"),
        Pair("Nhiều lượt xem nhất", "views"),
        Pair("Lượt xem hôm nay", "views_day"),
        Pair("Lượt xem tuần này", "views_week"),
        Pair("Lượt xem tháng này", "views_month"),
        Pair("Đánh giá cao nhất", "rating"),
        Pair("Nhiều Thần Chú nhất", "power"),
        Pair("Nhiều người theo dõi nhất", "follow"),
    ),
)

object SangChanhTeamFilters {
    fun getFilterList(): FilterList = FilterList(
        GenreFilter(
            listOf(
                Genre("Âu Cổ", "au-co"),
                Genre("Chuyển Sinh", "chuyen-sinh"),
                Genre("Drama", "drama"),
                Genre("Fantasy", "fantasy"),
                Genre("Hành Động", "hanh-dong"),
                Genre("Hiện Đại", "hien-dai"),
                Genre("Manhua", "manhua"),
                Genre("Manhwa", "manhwa"),
                Genre("Romance", "romance"),
                Genre("Tình Cảm", "tinh-cam"),
                Genre("Truyện Màu", "truyen-mau"),
                Genre("Xuyên Không", "xuyen-khong"),
            ),
        ),
        TypeFilter(),
        StatusFilter(),
        AgeRatingFilter(),
        TeamFilter(),
        RatingMinFilter(),
        RatingMaxFilter(),
        SortFilter(),
    )
}
