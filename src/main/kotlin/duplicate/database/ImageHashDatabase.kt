package com.fvlaenix.duplicate.database

import com.fvlaenix.duplicate.utils.ImageUtils.getGray
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage

private const val MAX_HEIGHT = 8
private const val MAX_WIDTH = 8


object ImageHashTable : Table() {
  val id = long("id").primaryKey()
  val group = varchar("group", 40)
  val timestamp = long("timestamp")
  val height = integer("height")
  val width = integer("width")

  val hashes = (0 until MAX_WIDTH).map { width ->
    (0 until MAX_HEIGHT).map { height ->
      integer("pixel_${height}_${width}")
    }
  }

  init {
    index("similarIndex", false, group, timestamp, height, width, *(hashes.flatten().toTypedArray()))
  }
}

class ImageHashConnector(private val database: Database) {
  companion object {
    var TEST_PIXEL_DISTANCE: Int? = null
    private const val PIXEL_DISTANCE = 24
    val REAL_PIXEL_DISTANCE
      get() = TEST_PIXEL_DISTANCE ?: PIXEL_DISTANCE
  }

  init {
    transaction(database) {
      SchemaUtils.create(ImageHashTable)
    }
  }

  data class ReturnResultAddAndCheck(val similarIds: List<Long>, val isAdded: Boolean)

  private fun Transaction.selectSimilarImage(
    group: String,
    timestamp: Long,
    image: BufferedImage,
    imageHash: List<List<Int>>
  ): List<Long> {
    return ImageHashTable.select {
      (ImageHashTable.height eq image.height)
        .and(ImageHashTable.width eq image.width)
        .and(ImageHashTable.group eq group)
        .and(ImageHashTable.timestamp less timestamp)
        .let {
          var accumulator = it
          (0 until MAX_WIDTH).map { width ->
            (0 until MAX_HEIGHT).map { height ->
              val column = ImageHashTable.hashes[width][height]
              val hash = imageHash[width][height]
              accumulator = accumulator.and(
                (column less hash + REAL_PIXEL_DISTANCE) and (column greater hash - REAL_PIXEL_DISTANCE)
              )
            }
          }
          accumulator
        }
    }.map { it[ImageHashTable.id] }
  }

  private fun Transaction.isExists(id: Long): Boolean =
    ImageHashTable.select { ImageHashTable.id eq id }.count() > 0

  private fun Transaction.addIfNotExists(
    id: Long,
    group: String,
    timestamp: Long,
    image: BufferedImage,
    imageHash: List<List<Int>>
  ): Boolean {
    if (isExists(id)) {
      return false
    } else {
      ImageHashTable.insert {
        it[ImageHashTable.id] = id
        it[ImageHashTable.group] = group
        it[ImageHashTable.timestamp] = timestamp
        it[ImageHashTable.height] = image.height
        it[ImageHashTable.width] = image.width
        (0 until MAX_WIDTH).map { width ->
          (0 until MAX_HEIGHT).map { height ->
            val column = hashes[width][height]
            val hash = imageHash[width][height]
            it[column] = hash
          }
        }
      }
      return true
    }
  }

  fun addImageWithCheck(id: Long, group: String, timestamp: Long, image: BufferedImage): ReturnResultAddAndCheck {
    val hashImage = Thumbnails.of(image).height(MAX_HEIGHT).width(MAX_WIDTH).asBufferedImage()
    val imageHash = (0 until MAX_WIDTH).map { width ->
      (0 until MAX_HEIGHT).map { height ->
        hashImage.getRGB(width, height).getGray()
      }
    }
    return transaction(database) {
      val ids = selectSimilarImage(group, timestamp, image, imageHash)
      val isAdded = addIfNotExists(id, group, timestamp, image, imageHash)
      return@transaction ReturnResultAddAndCheck(ids, isAdded)
    }
  }

  fun imageCheck(group: String, timestamp: Long, image: BufferedImage): List<Long> {
    val hashImage = Thumbnails.of(image).height(MAX_HEIGHT).width(MAX_WIDTH).asBufferedImage()
    val imageHash = (0 until MAX_WIDTH).map { width ->
      (0 until MAX_HEIGHT).map { height ->
        hashImage.getRGB(width, height).getGray()
      }
    }
    return transaction(database) {
      selectSimilarImage(group, timestamp, image, imageHash)
    }
  }

  fun deleteById(id: Long): Boolean = transaction(database) {
    ImageHashTable.deleteWhere { ImageHashTable.id eq id } > 0
  }
}





















