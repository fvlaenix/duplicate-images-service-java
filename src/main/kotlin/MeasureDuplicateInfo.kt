package com.fvlaenix

import com.fvlaenix.duplicate.database.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.max

open class EmporiumMeasureTable : Table() {
  val duplicateMessageId = varchar("duplicateMessageId", 50)
  val duplicateNumberInMessage = integer("duplicateNumberInMessage")
  val originalMessageId = varchar("originalMessageId", 50)
  val originalNumberInMessage = integer("originalNumberInMessage")
  val level = integer("level")
}

object DuplicateOldEmporiumTable : EmporiumMeasureTable()

object DuplicateNewEmporiumTable : EmporiumMeasureTable()

data class DuplicateInfoEmporium(
  val duplicateMessageId: String,
  val duplicateNumberInMessage: Int,
  val originalMessageId: String,
  val originalNumberInMessage: Int,
  val level: Int
)

class DuplicateEmporiumConnector(val database: Database, private val table: EmporiumMeasureTable) {
  init {
    transaction(database) {
      SchemaUtils.create(table)
    }
  }

  fun add(record: DuplicateInfoEmporium) = transaction(database) {
    table.insert {
      it[table.duplicateMessageId] = record.duplicateMessageId
      it[table.originalMessageId] = record.originalMessageId
      it[table.level] = record.level
      it[table.originalNumberInMessage] = record.originalNumberInMessage
      it[table.duplicateNumberInMessage] = record.duplicateNumberInMessage
    }
  }

  fun getAll(): List<DuplicateInfoEmporium> = transaction(database) {
    table.selectAll().map { get(it) }
  }

  fun get(resultRow: ResultRow) = DuplicateInfoEmporium(
    duplicateMessageId = resultRow[table.duplicateMessageId],
    duplicateNumberInMessage = resultRow[table.duplicateNumberInMessage],
    originalMessageId = resultRow[table.originalMessageId],
    originalNumberInMessage = resultRow[table.originalNumberInMessage],
    level = resultRow[table.level]
  )
}

class MeasureDuplicateInfo

private val LOGGER = Logger.getLogger(MeasureDuplicateInfo::class.java.name)

fun measure() {
  val coroutineContextPool = newFixedThreadPoolContext(16, "Measure machine context")

  LOGGER.log(Level.INFO, "Starting measure")

  val connection = Connector.connection
  LOGGER.log(Level.INFO, "Connection received")

  val newConnector = DuplicateEmporiumConnector(connection, DuplicateNewEmporiumTable)
  val oldConnector = DuplicateEmporiumConnector(connection, DuplicateOldEmporiumTable)
  val imageConnector = ImageConnector(connection)
  val imageHashConnector = ImageHashConnector(connection)

  val oldDuplicates = oldConnector.getAll()

  val semaphore = Semaphore(16)
  runBlocking {
    oldDuplicates.forEach { duplicateInfo ->
      semaphore.withPermit {
        launch(coroutineContextPool) {
          val originalImageId =
            imageConnector.getId(duplicateInfo.originalMessageId, duplicateInfo.originalNumberInMessage)
          val duplicateImageId =
            imageConnector.getId(duplicateInfo.duplicateMessageId, duplicateInfo.duplicateNumberInMessage)

          if (originalImageId == null) {
            LOGGER.log(
              Level.WARNING,
              "Can't find original info: ${duplicateInfo.originalMessageId}-${duplicateInfo.originalNumberInMessage}"
            )
            return@launch
          }
          if (duplicateImageId == null) {
            LOGGER.log(
              Level.WARNING,
              "Can't find duplicate info: ${duplicateInfo.duplicateMessageId}-${duplicateInfo.duplicateNumberInMessage}"
            )
            return@launch
          }

          val originalHash = imageHashConnector.getById(originalImageId)
          val duplicateHash = imageHashConnector.getById(duplicateImageId)

          if (originalHash == null) {
            LOGGER.log(
              Level.WARNING,
              "Can't find hash for original info: ${duplicateInfo.originalMessageId}-${duplicateInfo.originalNumberInMessage}"
            )
            return@launch
          }
          if (duplicateHash == null) {
            LOGGER.log(
              Level.WARNING,
              "Can't find hash for duplicate info: ${duplicateInfo.duplicateMessageId}-${duplicateInfo.duplicateNumberInMessage}"
            )
            return@launch
          }

          var maxDistance = 0
          (0 until MAX_WIDTH).forEach { width ->
            (0 until MAX_HEIGHT).forEach { height ->
              maxDistance = max(originalHash.hash[width][height] - duplicateHash.hash[width][height], maxDistance)
            }
          }

          val newDuplicateInfo = duplicateInfo.copy(level = maxDistance)

          newConnector.add(newDuplicateInfo)
        }
      }
    }
  }

}