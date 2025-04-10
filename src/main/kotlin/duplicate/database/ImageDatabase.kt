package duplicate.database

import duplicate.utils.ImageUtils
import duplicate.utils.LongBlobUtils.longBlob
import org.jetbrains.exposed.dao.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.util.logging.Level
import java.util.logging.Logger
import javax.sql.rowset.serial.SerialBlob
import kotlin.io.path.Path
import kotlin.io.path.extension

private const val IS_LONG_BLOB: Boolean = true

data class Image(
  val id: Long,
  val group: String,
  val messageId: String,
  val numberInMessage: Int,
  val additionalInfo: String,
  val fileName: String,
  val image: BufferedImage
)

object ImageTable : LongIdTable() {
  val group = varchar("group", 64)
  val messageId = varchar("messageId", 64)
  val numberInMessage = integer("numberInMessage")
  val additionalInfo = varchar("additionalInfo", 400)
  val fileName = varchar("fileName", 255)
  val image = if (IS_LONG_BLOB) longBlob("image") else blob("image")

  init {
    index(true, messageId, numberInMessage)
  }
}

private val LOGGER: Logger = Logger.getLogger(ImageConnector::class.java.name)

class ImageConnector(private val database: Database) {
  init {
    transaction(database) {
      SchemaUtils.create(ImageTable)
    }
  }

  fun <T> withConnect(body: Transaction.() -> T): T = transaction(database, body)

  private fun Transaction.getIdConnected(messageId: String, numberInMessage: Int) = ImageTable
    .slice(ImageTable.id, ImageTable.messageId, ImageTable.numberInMessage)
    .select { (ImageTable.messageId eq messageId) and (ImageTable.numberInMessage eq numberInMessage) }
    .map { it[ImageTable.id].value }.singleOrNull()

  fun getId(messageId: String, numberInMessage: Int): Long? = withConnect {
    getIdConnected(messageId, numberInMessage)
  }

  private fun Transaction.getConnected(messageId: String, numberInMessage: Int) = ImageTable
    .select { (ImageTable.messageId eq messageId) and (ImageTable.numberInMessage eq numberInMessage) }
    .map { get(it) }.singleOrNull()

  fun get(messageId: String, numberInMessage: Int): Image? = withConnect {
    getConnected(messageId, numberInMessage)
  }

  fun findById(id: Long): Image? = withConnect {
    ImageTable
      .select { ImageTable.id eq id }
      .map { get(it) }.singleOrNull()
  }

  private fun Transaction.existsConnected(messageId: String, numberInMessage: Int) =
    getIdConnected(messageId, numberInMessage) != null

  fun new(
    group: String,
    messageId: String,
    numberInMessage: Int,
    additionalInfo: String,
    fileName: String,
    image: BufferedImage
  ): Long {
    val blob = ImageUtils.getImageBlob(image, Path(fileName).extension)
    return withConnect {
      if (existsConnected(messageId, numberInMessage)) {
        return@withConnect getConnected(messageId, numberInMessage)!!.id
      }
      val idValue = ImageTable.insert {
        it[ImageTable.group] = group
        it[ImageTable.messageId] = messageId
        it[ImageTable.numberInMessage] = numberInMessage
        it[ImageTable.additionalInfo] = additionalInfo
        it[ImageTable.fileName] = fileName
        it[ImageTable.image] = blob
      } get ImageTable.id
      idValue.value
    }
  }

  /**
   * Gets the total number of images in the database
   */
  fun getTotalImagesCount(): Int = withConnect {
    ImageTable.selectAll().count().toInt()
  }

  /**
   * Gets a batch of image IDs with pagination support
   *
   * @param offset offset from the beginning of the selection
   * @param limit maximum number of records in the selection
   * @return list of image IDs
   */
  fun getImageIdsBatch(offset: Int, limit: Int): List<Long> = withConnect {
    ImageTable.slice(ImageTable.id)
      .selectAll()
      .orderBy(ImageTable.id to SortOrder.ASC)
      .limit(limit, offset)
      .map { it[ImageTable.id].value }
  }

  companion object {
    fun get(resultRow: ResultRow): Image {
      val image = ImageUtils.getImageFromBlob(SerialBlob(resultRow[ImageTable.image]))
      if (image == null) {
        LOGGER.log(
          Level.SEVERE,
          "Failed image read. Id: ${resultRow[ImageTable.messageId]}-${resultRow[ImageTable.numberInMessage]}"
        )
        throw ImageReadException(resultRow[ImageTable.id].value)
      }
      return Image(
        id = resultRow[ImageTable.id].value,
        group = resultRow[ImageTable.group],
        messageId = resultRow[ImageTable.messageId],
        numberInMessage = resultRow[ImageTable.numberInMessage],
        additionalInfo = resultRow[ImageTable.additionalInfo],
        fileName = resultRow[ImageTable.fileName],
        image = image
      )
    }
  }
}

class ImageReadException(id: Long) : Exception("Image $id could not be read")