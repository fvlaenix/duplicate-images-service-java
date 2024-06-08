package com.fvlaenix.duplicate.database

import com.fvlaenix.duplicate.database.ImageConnector.Companion.lessThenTolerance
import com.fvlaenix.duplicate.database.ImageConnector.Companion.listSum
import com.fvlaenix.duplicate.database.ImageConnector.Companion.sqr
import com.fvlaenix.duplicate.image.ComparingPictures.TOLERANCE_PER_POINT
import com.fvlaenix.duplicate.protobuf.Size
import com.fvlaenix.duplicate.protobuf.size
import com.fvlaenix.duplicate.utils.ImageUtils
import com.fvlaenix.duplicate.utils.ImageUtils.getBlue
import com.fvlaenix.duplicate.utils.ImageUtils.getGreen
import com.fvlaenix.duplicate.utils.ImageUtils.getRed
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.util.logging.Level
import java.util.logging.Logger
import javax.sql.rowset.serial.SerialBlob
import kotlin.io.path.Path
import kotlin.io.path.extension

class Image(
  val group: String,
  val imageId: String, // unique for each image
  val additionalInfo: String,
  val image: BufferedImage,
  val size: Size,
  val fileName: String,
  val timeStamp: Long,
  val leftUpper: RGB,
  val center: RGB,
  val rightBottom: RGB
) {
  data class RGB(val red: Int, val green: Int, val blue: Int)
}

object ImageTable : Table() {
  val group = varchar("group", 40)
  val imageId = varchar("imageId", 400).primaryKey()
  val additionalInfo = varchar("additionalInfo", 400)
  val image = blob("image")
  val height = integer("height")
  val width = integer("width")
  val fileName = varchar("fileName", 255)
  val timestamp = long("timestamp")

  class RGB(table: Table, id : String, val getPixel: (BufferedImage) -> Int) {
    private val red = table.integer("red$id").nullable()
    private val green = table.integer("green$id").nullable()
    private val blue = table.integer("blue$id").nullable()

    fun toList() : List<Column<Int?>> = listOf(red, green, blue)
    fun toArray() : Array<Column<Int?>> = arrayOf(red, green, blue)

    fun toSimilar(image: BufferedImage): Op<Boolean> {
      return listOf(
        (red.minus(getRed(image))).sqr(),
        (green.minus(getGreen(image))).sqr(),
        (blue.minus(getBlue(image))).sqr(),
      ).listSum().lessThenTolerance()
    }

    private fun getRed(image: BufferedImage): Int {
      return getPixel(image).getRed()
    }

    private fun getGreen(image: BufferedImage): Int {
      return getPixel(image).getGreen()
    }

    private fun getBlue(image: BufferedImage): Int {
      return getPixel(image).getBlue()
    }

    fun insert(insertStatement: InsertStatement<Number>, rgb: Image.RGB) {
      insertStatement[red] = rgb.red
      insertStatement[green] = rgb.green
      insertStatement[blue] = rgb.blue
    }

    fun insert(insertStatement: InsertStatement<Number>, image: BufferedImage?) {
      if (image == null) {
        insertStatement[red] = null
        insertStatement[green] = null
        insertStatement[blue] = null
      } else {
        insert(insertStatement, getRGB(image))
      }
    }

    fun getRGB(image: BufferedImage): Image.RGB {
      return Image.RGB(getRed(image), getGreen(image), getBlue(image))
    }

    fun getRGB(resultRow: ResultRow): Image.RGB {
      val red = resultRow[red]!!
      val green = resultRow[green]!!
      val blue = resultRow[blue]!!
      return Image.RGB(red, green, blue)
    }
  }

  val leftUpper = RGB(this, "0") { image -> image.getRGB(0, 0) }
  val center = RGB(this, "1") { image -> image.getRGB(image.width / 2, image.height / 2) }
  val rightBottom = RGB(this, "2") { image -> image.getRGB(image.width - 1, image.height - 1) }

  init {
    index("imageId", false, imageId)
    index("similarIndex", false, group, timestamp, *(leftUpper.toArray()), *(center.toArray()), *(rightBottom.toArray()), height, width)
  }
}

private val LOGGER: Logger = Logger.getLogger(ImageConnector::class.java.simpleName)

class ImageConnector(private val database: Database) {

  init {
    transaction(database) {
      SchemaUtils.create(ImageTable)
    }
  }

  private fun getTransactionalSimilarImages(image: BufferedImage, group: String, timeStamp: Long): List<String> {
    return ImageTable
      .select {
        (ImageTable.leftUpper.toSimilar(image))
          .and(ImageTable.center.toSimilar(image))
          .and(ImageTable.rightBottom.toSimilar(image))
          .and(ImageTable.height eq image.height)
          .and(ImageTable.width eq image.width)
          .and(ImageTable.group eq group)
          .and(ImageTable.timestamp less timeStamp)
      }.map { it[ImageTable.imageId] }.apply {
        if (this.size > 100) LOGGER.log(Level.WARNING, "Count of taken images is ${this.size}")
      }
  }
  
  fun getSimilarImages(image: BufferedImage, group: String, timeStamp: Long) : List<String> =
    transaction(database) {
      getTransactionalSimilarImages(image, group, timeStamp)
    }

  fun getImageById(imageId: String): Image? =
    transaction(database) {
      ImageTable.select { ImageTable.imageId eq imageId }.map { get(it) }.firstNotNullOfOrNull { it }
    }
  
  fun isImageExistsById(imageId: String): Boolean =
    transaction(database) { 
      ImageTable.select { ImageTable.imageId eq imageId }.count() > 0
    }
  
  fun deleteById(imageId: String): Boolean =
    transaction(database) { 
      ImageTable.deleteWhere { ImageTable.imageId eq imageId } > 0
    }

  private fun addTransactionalImage(
    group: String,
    imageId: String,
    additionalInfo: String,
    image: BufferedImage,
    fileName: String,
    timeStamp: Long,
    blob: SerialBlob
  ): Boolean {
    val images = ImageTable.select { ImageTable.imageId eq imageId }.mapNotNull { get(it) }
    if (images.isNotEmpty()) return false
    else ImageTable.insert {
      it[ImageTable.group] = group
      it[ImageTable.imageId] = imageId
      it[ImageTable.additionalInfo] = additionalInfo
      it[ImageTable.image] = blob
      it[ImageTable.height] = image.height
      it[ImageTable.width] = image.width
      it[ImageTable.fileName] = fileName
      it[ImageTable.timestamp] = timeStamp
      leftUpper.insert(it, image)
      center.insert(it, image)
      rightBottom.insert(it, image)
    }
    return true
  }
  
  data class ReturnResultAddAndCheck(val similarIds: List<String>, val isAdded: Boolean)
  
  fun addImageWithCheck(group: String, imageId: String, additionalInfo: String, image: BufferedImage, fileName: String, timeStamp: Long): ReturnResultAddAndCheck {
    val blob = ImageUtils.getImageBlob(image, Path(fileName).extension) ?: return ReturnResultAddAndCheck(emptyList(), false)
    return transaction(database) { 
      val list = getTransactionalSimilarImages(image, group, timeStamp)
      val addingResult = addTransactionalImage(group, imageId, additionalInfo, image, fileName, timeStamp, blob)
      ReturnResultAddAndCheck(list, addingResult)
    }
  }

  class Brackets<T>(private val e1: ExpressionWithColumnType<T>, override val columnType: IColumnType) : ExpressionWithColumnType<T>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append("(", e1, ")") }
  }

  companion object {
    fun <T> ExpressionWithColumnType<T>.sqr() = Brackets(
      TimesOp(Brackets(this, this.columnType), Brackets(this, this.columnType), this.columnType),
      this.columnType
    )

    fun <T> List<ExpressionWithColumnType<T>>.listSum(): ExpressionWithColumnType<T> {
      check(this.isNotEmpty())
      var sum = this[0]
      (1 until this.size).forEach { sum = sum.plus(this[it]) }
      return sum
    }

    fun <T> ExpressionWithColumnType<T>.lessThenTolerance(): Op<Boolean> =
      this.less(TOLERANCE_PER_POINT)

    fun get(resultRow: ResultRow): Image? {
      val image = ImageUtils.getImageFromBlob(SerialBlob(resultRow[ImageTable.image]))
      return if (image == null) {
        LOGGER.log(Level.SEVERE, "Failed image read. Id: ${resultRow[ImageTable.imageId]}")
        null
      } else {
        Image(
          resultRow[ImageTable.group],
          resultRow[ImageTable.imageId],
          resultRow[ImageTable.additionalInfo],
          image,
          size { x = resultRow[ImageTable.width]; y = resultRow[ImageTable.height] },
          resultRow[ImageTable.fileName],
          resultRow[ImageTable.timestamp],
          ImageTable.leftUpper.getRGB(resultRow),
          ImageTable.center.getRGB(resultRow),
          ImageTable.rightBottom.getRGB(resultRow)
        )
      }
    }
  }
}