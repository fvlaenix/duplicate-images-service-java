package duplicate.utils

import duplicate.database.MAX_HEIGHT
import duplicate.database.MAX_WIDTH
import kotlin.math.abs

object HashUtils {
  fun distance(image1: List<List<Int>>, image2: List<List<Int>>): Int {
    var max = 0
    (0 until MAX_WIDTH).forEach { width ->
      (0 until MAX_HEIGHT).forEach { height ->
        val hash = image1[width][height]
        val imageInfoHash = image2[width][height]
        if (abs(hash - imageInfoHash) > max) max = abs(hash - imageInfoHash)
      }
    }
    return max
  }
}