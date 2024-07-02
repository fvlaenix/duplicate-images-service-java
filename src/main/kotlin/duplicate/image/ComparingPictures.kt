package com.fvlaenix.duplicate.image

import com.fvlaenix.duplicate.utils.ImageUtils.getBlue
import com.fvlaenix.duplicate.utils.ImageUtils.getGreen
import com.fvlaenix.duplicate.utils.ImageUtils.getRed
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.random.Random

object ComparingPictures {
  private const val REAL_TOLERANCE = 17000 // 1292
  var TEST_TOLERANCE: Int? = null
  private val TOLERANCE_PER_POINT
    get() = TEST_TOLERANCE ?: REAL_TOLERANCE

  private const val COUNT_RANDOM_POINTS = 10

  private fun colorDiff(o1: Int, o2: Int): Long? {
    if (o1 == o2) {
      return 0
    }

    val red1: Int = o1.getRed()
    val green1: Int = o1.getGreen()
    val blue1: Int = o1.getBlue()
    val red2: Int = o2.getRed()
    val green2: Int = o2.getGreen()
    val blue2: Int = o2.getBlue()

    val result = (red1.toLong() - red2) * (red1 - red2) + (green1 - green2) * (green1 - green2) + (blue1 - blue2) * (blue1 - blue2)
    return if (result > TOLERANCE_PER_POINT) {
      null
    } else {
      result
    }
  }

  fun comparePictures(image1: BufferedImage, image2: BufferedImage): Long? {
    if (image1.width != image2.width || image1.height != image2.height) return null
    var max = 0L
    (0..COUNT_RANDOM_POINTS).forEach { _ ->
      val x = Random.nextInt(image1.width)
      val y = Random.nextInt(image1.height)
      val diff = colorDiff(image1.getRGB(x, y), image2.getRGB(x, y)) ?: return null
      max = max(max, diff)
    }
    (0 until image1.height).forEach { y ->
      (0 until image1.width).forEach { x ->
        val diff = colorDiff(image1.getRGB(x, y), image2.getRGB(x, y)) ?: return null
        max = max(max, diff)
      }
    }
    return max
  }
}