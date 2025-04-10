package duplicate.database

import org.jetbrains.exposed.dao.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Data class representing image metadata
 */
data class ImageMetadata(
  val id: Long,
  val group: String,
  val messageId: String,
  val numberInMessage: Int,
  val additionalInfo: String,
  val fileName: String
)

object ImageTable : LongIdTable() {
  val group = varchar("group", 64)
  val messageId = varchar("messageId", 64)
  val numberInMessage = integer("numberInMessage")
  val additionalInfo = varchar("additionalInfo", 400)
  val fileName = varchar("fileName", 255)

  init {
    index(true, messageId, numberInMessage)
  }
}

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

  private fun Transaction.existsConnected(messageId: String, numberInMessage: Int) =
    getIdConnected(messageId, numberInMessage) != null

  fun new(
    group: String,
    messageId: String,
    numberInMessage: Int,
    additionalInfo: String,
    fileName: String
  ): Long {
    return withConnect {
      if (existsConnected(messageId, numberInMessage)) {
        return@withConnect getIdConnected(messageId, numberInMessage)!!
      }
      val idValue = ImageTable.insert {
        it[ImageTable.group] = group
        it[ImageTable.messageId] = messageId
        it[ImageTable.numberInMessage] = numberInMessage
        it[ImageTable.additionalInfo] = additionalInfo
        it[ImageTable.fileName] = fileName
      } get ImageTable.id
      idValue.value
    }
  }

  /**
   * Get image metadata by ID
   */
  fun getMetadataById(id: Long): ImageMetadata? = withConnect {
    ImageTable.select { ImageTable.id eq id }
      .map { getMetadata(it) }.singleOrNull()
  }

  /**
   * Get image metadata by messageId and numberInMessage
   */
  fun getMetadataByMessage(messageId: String, numberInMessage: Int): ImageMetadata? = withConnect {
    ImageTable.select { (ImageTable.messageId eq messageId) and (ImageTable.numberInMessage eq numberInMessage) }
      .map { getMetadata(it) }.singleOrNull()
  }

  /**
   * Gets the total number of images in the database
   */
  fun getTotalImagesCount(): Int = withConnect {
    ImageTable.selectAll().count()
  }

  /**
   * Gets a batch of image IDs with pagination support
   */
  fun getImageIdsBatch(offset: Int, limit: Int): List<Long> = withConnect {
    ImageTable.slice(ImageTable.id)
      .selectAll()
      .orderBy(ImageTable.id to SortOrder.ASC)
      .limit(limit, offset)
      .map { it[ImageTable.id].value }
  }

  /**
   * Create metadata object from ResultRow
   */
  private fun getMetadata(resultRow: ResultRow): ImageMetadata {
    return ImageMetadata(
      id = resultRow[ImageTable.id].value,
      group = resultRow[ImageTable.group],
      messageId = resultRow[ImageTable.messageId],
      numberInMessage = resultRow[ImageTable.numberInMessage],
      additionalInfo = resultRow[ImageTable.additionalInfo],
      fileName = resultRow[ImageTable.fileName]
    )
  }

  fun deleteById(imageId: Long): Int = withConnect {
    ImageTable.deleteWhere { ImageTable.id eq imageId }
  }
}