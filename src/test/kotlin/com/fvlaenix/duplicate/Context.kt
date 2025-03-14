package com.fvlaenix.duplicate

import duplicate.database.ImageHashConnector
import duplicate.database.ImageHashTable
import duplicate.database.ImageTable
import duplicate.image.ComparingMachine
import duplicate.image.ComparingPictures
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object Context {
  private fun <T> withDatabaseContext(body: (Database) -> T): T {
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