package duplicate.utils

import duplicate.database.MAX_COUNT_INDICES
import duplicate.database.MAX_HEIGHT
import duplicate.database.MAX_WIDTH

object IndicesUtils {

  class IndiciesIndexes(private val indices: List<Pair<Int, Int>>) {
    fun <T> translateList(list: List<List<T>>): List<T> =
      indices.map { item -> list[item.first][item.second] }
  }

  fun getIndices(
    width: Int = MAX_WIDTH,
    height: Int = MAX_HEIGHT,
    count: Int = MAX_COUNT_INDICES
  ): List<IndiciesIndexes> {
    check(width == 8 && height == 8 && count == 8)
    return (0..1).flatMap { startX ->
      (0..3).map { startY ->
        val points = (0..3).flatMap { titleX ->
          (0..1).map { titleY ->
            Pair(startX + titleX * 2, startY + titleY * 4)
          }
        }
        IndiciesIndexes(points)
      }
    }
  }
}