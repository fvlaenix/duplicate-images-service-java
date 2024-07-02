package com.fvlaenix

import com.fvlaenix.duplicate.database.*
import com.fvlaenix.duplicate.utils.ImageUtils
import com.fvlaenix.duplicate.utils.ImageUtils.getGray
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.Path
import kotlin.io.path.extension

class Migration

private val LOGGER = Logger.getLogger(Migration::class.java.name)

@Serializable
data class OldImageId(val messageId: String, val numberInMessage: Int)

val coroutineContextPool = newFixedThreadPoolContext(16, "Migration machine context")

fun migration() {
  LOGGER.log(Level.INFO, "Starting migration")

  val connection = Connector.connection
  LOGGER.log(Level.INFO, "Connection received")

  val oldConnector = ImageOldConnector(connection)
  val imageConnector = ImageConnector(connection)

  val ids = transaction(connection) {
    if (!ImageOldTable.exists()) {
      LOGGER.log(Level.SEVERE, "Can't found old image table")
      return@transaction null
    }
    if (!ImageTable.exists()) {
      SchemaUtils.create(ImageTable)
    }
    if (!ImageHashTable.exists()) {
      SchemaUtils.create(ImageHashTable)
    }
    ImageOldTable.slice(ImageOldTable.imageId).selectAll().map {
      Json.decodeFromString<OldImageId>(it[ImageOldTable.imageId])
    }
  } ?: return
  val semaphore = Semaphore(16)
  runBlocking {
    ids.forEachIndexed { index, id ->
      semaphore.withPermit {
        launch(coroutineContextPool) {
          if (index % 10 == 0) LOGGER.log(Level.INFO, "Migration process: [$index/${ids.size}]")
          val old = oldConnector.getImageById(Json.encodeToString(id))
          if (old == null) {
            LOGGER.log(Level.SEVERE, "Can't find id $id")
            return@launch
          }
          val blob = ImageUtils.getImageBlob(old.image, Path(old.fileName).extension)

          if (imageConnector.get(id.messageId, id.numberInMessage) != null) return@launch

          val hashImage = Thumbnails.of(old.image).forceSize(MAX_WIDTH, MAX_HEIGHT).asBufferedImage()
          val imageHash = (0 until MAX_WIDTH).map { width ->
            (0 until MAX_HEIGHT).map { height ->
              hashImage.getRGB(width, height).getGray()
            }
          }
          transaction(connection) {
            val idValue = ImageTable.insert {
              it[ImageTable.group] = old.group
              it[ImageTable.messageId] = id.messageId
              it[ImageTable.numberInMessage] = id.numberInMessage
              it[ImageTable.additionalInfo] = old.additionalInfo
              it[ImageTable.fileName] = old.fileName
              it[ImageTable.image] = blob
            } get ImageTable.id
            val tableId = idValue.value
            ImageHashTable.insert {
              it[ImageHashTable.id] = tableId
              it[ImageHashTable.group] = old.group
              it[ImageHashTable.timestamp] = old.timeStamp
              it[ImageHashTable.height] = old.image.height
              it[ImageHashTable.width] = old.image.width
              (0 until MAX_WIDTH).map { width ->
                (0 until MAX_HEIGHT).map { height ->
                  val column = hashes[width][height]
                  val hash = imageHash[width][height]
                  it[column] = hash
                }
              }
            }
          }
        }
      }
    }
  }
}