package com.fvlaenix.duplicate.database

import com.fvlaenix.duplicate.protobuf.Size
import com.fvlaenix.duplicate.protobuf.size
import com.fvlaenix.duplicate.utils.ImageUtils
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.util.logging.Level
import java.util.logging.Logger
import javax.sql.rowset.serial.SerialBlob

@Deprecated(message = "Use new Image")
class ImageOld(
  val group: String,
  val imageId: String, // unique for each image
  val additionalInfo: String,
  val image: BufferedImage,
  val size: Size,
  val fileName: String,
  val timeStamp: Long
) {
  data class RGB(val red: Int, val green: Int, val blue: Int)
}

@Deprecated(message = "Use new Image")
object ImageOldTable : Table() {
  val group = varchar("group", 40)
  val imageId = varchar("imageId", 400).primaryKey()
  val additionalInfo = varchar("additionalInfo", 400)
  val image = blob("image")
  val height = integer("height")
  val width = integer("width")
  val fileName = varchar("fileName", 255)
  val timestamp = long("timestamp")

  class RGB(table: Table, id: String, val getPixel: (BufferedImage) -> Int) {
    private val red = table.integer("red$id").nullable()
    private val green = table.integer("green$id").nullable()
    private val blue = table.integer("blue$id").nullable()

    fun toList(): List<Column<Int?>> = listOf(red, green, blue)
    fun toArray(): Array<Column<Int?>> = arrayOf(red, green, blue)
  }

  val leftUpper = RGB(this, "0") { image -> image.getRGB(0, 0) }
  val center = RGB(this, "1") { image -> image.getRGB(image.width / 2, image.height / 2) }
  val rightBottom = RGB(this, "2") { image -> image.getRGB(image.width - 1, image.height - 1) }

  init {
    index("imageId", false, imageId)
    index(
      "similarIndex",
      false,
      group,
      timestamp,
      *(leftUpper.toArray()),
      *(center.toArray()),
      *(rightBottom.toArray()),
      height,
      width
    )
  }
}

private val LOGGER: Logger = Logger.getLogger(ImageOldConnector::class.java.simpleName)

@Deprecated(message = "Use new Image")
class ImageOldConnector(private val database: Database) {

  init {
    transaction(database) {
      SchemaUtils.create(ImageOldTable)
    }
  }

  fun getImageById(imageId: String): ImageOld? =
    transaction(database) {
      ImageOldTable.select { ImageOldTable.imageId eq imageId }.map { get(it) }.firstNotNullOfOrNull { it }
    }

  fun get(resultRow: ResultRow): ImageOld? {
    val image = ImageUtils.getImageFromBlob(SerialBlob(resultRow[ImageOldTable.image]))
    return if (image == null) {
      LOGGER.log(Level.SEVERE, "Failed image read. Id: ${resultRow[ImageOldTable.imageId]}")
      null
    } else {
      ImageOld(
        resultRow[ImageOldTable.group],
        resultRow[ImageOldTable.imageId],
        resultRow[ImageOldTable.additionalInfo],
        image,
        size { x = resultRow[ImageOldTable.width]; y = resultRow[ImageOldTable.height] },
        resultRow[ImageOldTable.fileName],
        resultRow[ImageOldTable.timestamp]
      )
    }
  }
}