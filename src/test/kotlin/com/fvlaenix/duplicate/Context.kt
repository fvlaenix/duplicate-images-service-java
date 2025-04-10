package com.fvlaenix.duplicate

import duplicate.database.ImageHashConnector
import duplicate.database.ImageHashTable
import duplicate.database.ImageTable
import duplicate.image.ComparingMachine
import duplicate.image.ComparingPictures
import duplicate.s3.S3Storage
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import java.util.logging.Logger

object Context {
  private val logger = Logger.getLogger(Context::class.java.name)

  private fun <T> withDatabaseContext(body: (Database) -> T): T {
    logger.info("Starting LocalStack for S3 testing")
    S3TestUtils.startLocalStack()
    val testProperties = S3TestUtils.createTestS3Properties()
    S3Storage.setTestingMode(testProperties)

    val properties = Properties().apply {
      load(Context::class.java.getResourceAsStream("/database/testDatabase.properties")!!)
    }
    val configuration = Database.connect(
      url = properties.getProperty("url")!!,
      driver = properties.getProperty("driver")!!,
      user = properties.getProperty("user")!!,
      password = properties.getProperty("password")!!
    )

    return try {
      body(configuration)
    } catch (e: Exception) {
      throw Exception("While executing body in database context", e)
    } finally {
      // Clean up S3 test environment
      logger.info("Cleaning up S3 test environment")
      S3Storage.resetTestingMode()
      S3TestUtils.stopLocalStack()
    }
  }

  fun <T> withImageHashContext(body: (ImageHashConnector) -> T) = withDatabaseContext { database ->
    ComparingPictures.TEST_TOLERANCE = 1
    ImageHashConnector.TEST_PIXEL_DISTANCE = 0
    val connector = ImageHashConnector(database)
    return@withDatabaseContext try {
      body(connector)
    } finally {
      transaction(database) {
        SchemaUtils.drop(ImageHashTable)
        SchemaUtils.drop(ImageTable)
      }
    }
  }

  fun <T> withComparingMachine(pictureTolerance: Int = 1, pixelTolerance: Int = 0, body: (ComparingMachine) -> T): T =
    withDatabaseContext { database ->
      ComparingPictures.TEST_TOLERANCE = pictureTolerance
      ImageHashConnector.TEST_PIXEL_DISTANCE = pixelTolerance

      val comparingMachine = ComparingMachine(database)

      return@withDatabaseContext try {
        body(comparingMachine)
      } finally {
        transaction(database) {
          SchemaUtils.drop(ImageTable)
          SchemaUtils.drop(ImageHashTable)
        }
      }
    }
}