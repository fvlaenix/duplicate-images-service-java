package com.fvlaenix.duplicate.database

import com.fvlaenix.duplicate.utils.ImageUtils
import org.jetbrains.exposed.dao.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.util.logging.Level
import java.util.logging.Logger
import javax.sql.rowset.serial.SerialBlob
import kotlin.io.path.Path
import kotlin.io.path.extension

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
  val image = blob("image")

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
        resultRow[ImageTable.id].value,
        resultRow[ImageTable.group],
        resultRow[ImageTable.messageId],
        resultRow[ImageTable.numberInMessage],
        resultRow[ImageTable.additionalInfo],
        resultRow[ImageTable.fileName],
        image
      )
    }
  }
}

class ImageReadException(id: Long) : Exception("Image $id could not be read")