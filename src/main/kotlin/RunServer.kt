package com.fvlaenix

import com.fvlaenix.duplicate.DuplicateImagesServer
import com.fvlaenix.duplicate.database.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger

const val LOGGING_PATH = "/logging.properties"

class RunServer

@Serializable
data class OldImageId(val guildId: String, val channelId: String, val messageId: String, val numberInMessage: Int) {
  fun toNew(): NewImageId = NewImageId(messageId, numberInMessage)
}

@Serializable
data class NewImageId(val messageId: String, val numberInMessage: Int)

fun isNew(duplicateInfo: DuplicateInfo): Boolean {
  return !runCatching { Json.decodeFromString<NewImageId>(duplicateInfo.duplicateImageId) }.isFailure
}

fun isNew(image: Image): Boolean {
  return !runCatching { Json.decodeFromString<NewImageId>(image.imageId) }.isFailure
}

fun translateImageInfo(messageId: String): String =
  Json.encodeToString(Json.decodeFromString<OldImageId>(messageId).toNew())

fun debug() {
  val connection = Connector.connection
  val oldDuplicateInfo = transaction(connection) {
    DuplicateInfoTable.selectAll().map { DuplicateInfoConnector.get(it) }
  }
  for (duplicateInfo in oldDuplicateInfo) {
    if (!isNew(duplicateInfo)) {
      transaction(connection) {
        DuplicateInfoTable.update({
          (DuplicateInfoTable.duplicateImageId eq duplicateInfo.duplicateImageId) and 
                  (DuplicateInfoTable.originalImageId eq duplicateInfo.originalImageId)
        }) {
          it[duplicateImageId] = translateImageInfo(duplicateInfo.duplicateImageId)
          it[originalImageId] = translateImageInfo(duplicateInfo.originalImageId)
        }
      }
    }
  }
  
  val oldImageInfo = transaction(connection) {
    ImageTable.selectAll().mapNotNull { ImageConnector.get(it) }
  }
  for (image in oldImageInfo) {
    if (!isNew(image)) {
      transaction(connection) {
        ImageTable.update({
          ImageTable.imageId eq image.imageId
        }) {
          it[imageId] = translateImageInfo(image.imageId)
        }
      }
    }
  }
}

fun main() {
  debug()
  return
  
  try {
    LogManager.getLogManager().readConfiguration(RunServer::class.java.getResourceAsStream(LOGGING_PATH))
  } catch (e: Exception) {
    throw IllegalStateException("Failed while trying to read logs", e)
  }
  val runServerLog = Logger.getLogger(RunServer::class.java.name)
  val connection = Connector.connection
  runServerLog.log(Level.INFO, "Starting server")
  val server = DuplicateImagesServer(
    port = 50055,
    database = connection
  )
  server.start()
  runServerLog.log(Level.INFO, "Started server")
  server.blockUntilShutdown()
}