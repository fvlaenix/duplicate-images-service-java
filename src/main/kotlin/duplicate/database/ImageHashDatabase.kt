package duplicate.database

import duplicate.utils.HashUtils
import duplicate.utils.ImageUtils.getGray
import duplicate.utils.IndicesUtils
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage

const val MAX_HEIGHT = 8
const val MAX_WIDTH = 8
const val MAX_COUNT_INDICES = 8

data class ImageHash(
  val id: Long,
  val group: String,
  val timestamp: Long,
  val height: Int,
  val width: Int,
  val hash: List<List<Int>>
)

object ImageHashTable : Table() {
  val id = long("id").primaryKey()
  val group = varchar("group", 40)
  val timestamp = long("timestamp")
  val height = integer("height")
  val width = integer("width")

  data class HashInfo(val hashColumn: Column<Int>, val height: Int, val width: Int)
  
  val hashes = (0 until MAX_WIDTH).map { width ->
    (0 until MAX_HEIGHT).map { height ->
      HashInfo(integer("pixel_${height}_${width}"), height, width)
    }
  }

  val columnGroups = IndicesUtils.getIndices()
    .map { it.translateList(hashes) }
  
  init {
    columnGroups.forEachIndexed { index, columnGroup ->
      index(
        "similarIndex-$index",
        false,
        group,
        timestamp,
        height,
        width,
        *(columnGroup.map { it.hashColumn }.toTypedArray())
      )
    }
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

  private fun Transaction.selectSimilarImageConnected(
    group: String,
    timestamp: Long,
    height: Int,
    width: Int,
    imageHash: List<List<Int>>,
    pixelDistance: Int = REAL_PIXEL_DISTANCE
  ): List<Long> {
    fun getRequest(columnGroup: List<ImageHashTable.HashInfo>): List<Long> {
      return ImageHashTable.select {
        (ImageHashTable.height eq height)
          .and(ImageHashTable.width eq width)
          .and(ImageHashTable.group eq group)
          .and(ImageHashTable.timestamp less timestamp)
          .let {
            var accumulator = it

            columnGroup.forEach { columnInfo ->
              val hash = imageHash[columnInfo.width][columnInfo.height]
              accumulator = accumulator.and(
                (columnInfo.hashColumn lessEq hash + pixelDistance) and (columnInfo.hashColumn greaterEq hash - pixelDistance)
              )
            }

            accumulator
          }
      }.map { it[ImageHashTable.id] }
    }

    var currentIds = getRequest(ImageHashTable.columnGroups[0]).toSet()
    (1 until ImageHashTable.columnGroups.size).forEach { columnGroupIndex ->
      if (currentIds.isEmpty()) return emptyList()
      if (currentIds.size < 10) {
        val imageInfos = currentIds.mapNotNull { getById(it) }
        return imageInfos.filter { imageInfo ->
          if (imageInfo.group != group) return@filter false
          if (imageInfo.height != height) return@filter false
          if (imageInfo.width != width) return@filter false
          if (imageInfo.timestamp >= timestamp) return@filter false
          if (HashUtils.distance(imageHash, imageInfo.hash) > pixelDistance) return@filter false
          return@filter true
        }.map { it.id }
      }
      val columnGroup = ImageHashTable.columnGroups[columnGroupIndex]
      currentIds = currentIds.intersect(getRequest(columnGroup).toSet())
    }

    return currentIds.toList()
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
            it[column.hashColumn] = hash
          }
        }
      }
      return true
    }
  }

  fun addImageWithCheck(
    id: Long,
    group: String,
    timestamp: Long,
    image: BufferedImage,
    pixelDistance: Int = REAL_PIXEL_DISTANCE
  ): ReturnResultAddAndCheck {
    val imageHash = image.getHash()
    return transaction(database) {
      val ids = selectSimilarImageConnected(group, timestamp, image.height, image.width, imageHash, pixelDistance)
      val isAdded = addIfNotExists(id, group, timestamp, image, imageHash)
      return@transaction ReturnResultAddAndCheck(ids, isAdded)
    }
  }

  fun imageCheck(
    group: String,
    timestamp: Long,
    image: BufferedImage,
    pixelDistance: Int = REAL_PIXEL_DISTANCE
  ): List<Long> {
    val hashImage = Thumbnails.of(image).height(MAX_HEIGHT).width(MAX_WIDTH).asBufferedImage()
    val imageHash = (0 until MAX_WIDTH).map { width ->
      (0 until MAX_HEIGHT).map { height ->
        hashImage.getRGB(width, height).getGray()
      }
    }
    return transaction(database) {
      selectSimilarImageConnected(group, timestamp, image.height, image.width, imageHash, pixelDistance)
    }
  }

  fun deleteById(id: Long): Boolean = transaction(database) {
    ImageHashTable.deleteWhere { ImageHashTable.id eq id } > 0
  }

  fun get(resultRow: ResultRow): ImageHash = ImageHash(
    id = resultRow[ImageHashTable.id],
    group = resultRow[ImageHashTable.group],
    timestamp = resultRow[ImageHashTable.timestamp],
    height = resultRow[ImageHashTable.height],
    width = resultRow[ImageHashTable.width],
    hash = (0 until MAX_WIDTH).map { width ->
      (0 until MAX_HEIGHT).map { height ->
        resultRow[ImageHashTable.hashes[width][height].hashColumn]
      }
    }
  )

  fun getById(id: Long): ImageHash? = transaction(database) {
    ImageHashTable.select { ImageHashTable.id eq id }.map { get(it) }.singleOrNull()
  }
}

fun BufferedImage.getHash(): List<List<Int>> {
  val hashImage = Thumbnails.of(this).forceSize(MAX_WIDTH, MAX_HEIGHT).asBufferedImage()
  val imageHash = (0 until MAX_WIDTH).map { width ->
    (0 until MAX_HEIGHT).map { height ->
      hashImage.getRGB(width, height).getGray()
    }
  }
  return imageHash
}