package com.fvlaenix

import com.fvlaenix.duplicate.database.Connector
import com.fvlaenix.duplicate.database.ImageConnector
import com.fvlaenix.duplicate.database.ImageHashConnector
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.logging.Level
import java.util.logging.Logger

class MistakeCounter

private val LOGGER = Logger.getLogger(MistakeCounter::class.java.name)

private const val MAX_COUNTER = 7

data class ResultOfMistakeCounter(
  val id: Long,
  val results: List<Int>
)

object ResultOfMistakeCounterTable : Table() {
  val id = long("id").primaryKey()

  val counters = (0..MAX_COUNTER).map { count ->
    integer("counter_$count")
  }
}

class ResultOfMistakeCounterConnector(val database: Database) {
  init {
    transaction(database) {
      SchemaUtils.create(ResultOfMistakeCounterTable)
    }
  }

  fun add(counter: ResultOfMistakeCounter) = transaction(database) {
    ResultOfMistakeCounterTable.insert {
      it[id] = counter.id
      counters.zip(counter.results).forEach { (result, counter) ->
        it[result] = counter
      }
    }
  }

  fun exists(id: Long) = transaction(database) {
    ResultOfMistakeCounterTable.select { ResultOfMistakeCounterTable.id eq id }.empty().not()
  }
}

fun mistakeCounter() {
  LOGGER.info("Starting MistakeCounter")
  val coroutineContextPool = newFixedThreadPoolContext(16, "Mistake counter machine context")

  val connection = Connector.connection

  val imageConnector = ImageConnector(connection)
  val imageHashConnector = ImageHashConnector(connection)
  val mistakeCounterConnector = ResultOfMistakeCounterConnector(connection)

  val imageIds = imageConnector.getAllIds()

  val semaphore = Semaphore(16)

  runBlocking {
    imageIds.forEachIndexed { index, id ->
      semaphore.withPermit {
        launch(coroutineContextPool) {
          if (index % 10 == 0) LOGGER.log(Level.INFO, "Migration process: [$index/${imageIds.size}]")
          if (mistakeCounterConnector.exists(id)) return@launch
          val imageInfo = imageConnector.findByIdWithoutImage(id)
          if (imageInfo == null) {
            LOGGER.severe("No image with id $id found")
            return@launch
          }
          val imageHash = imageHashConnector.getById(id)
          if (imageHash == null) {
            LOGGER.severe("No hash with id $id found")
            return@launch
          }

          val counters = mutableListOf<Int>()
          for (pixelDistance in (0..MAX_COUNTER)) {
            val similarImages = imageHashConnector.selectSimilarImages(
              group = imageInfo.group,
              timestamp = imageHash.timestamp,
              height = imageHash.height,
              width = imageHash.width,
              imageHash = imageHash.hash,
              pixelDistance = pixelDistance
            )
            counters.add(similarImages.size)
          }

          mistakeCounterConnector.add(
            ResultOfMistakeCounter(
              id = id,
              results = counters
            )
          )
        }
      }
    }
  }

}