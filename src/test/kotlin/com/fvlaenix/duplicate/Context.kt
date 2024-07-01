package com.fvlaenix.duplicate

import com.fvlaenix.duplicate.database.ImageHashConnector
import com.fvlaenix.duplicate.database.ImageHashTable
import com.fvlaenix.duplicate.database.ImageTable
import com.fvlaenix.duplicate.image.ComparingMachine
import com.fvlaenix.duplicate.image.ComparingPictures
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
    ImageHashConnector.TEST_PIXEL_DISTANCE = 1
    val connector = ImageHashConnector(database)
    return@withDatabaseContext try {
      body(connector)
    } finally {
      transaction(database) {
        SchemaUtils.drop(ImageHashTable)
      }
    }
  }
  
  fun <T> withComparingMachine(body: (ComparingMachine) -> T): T = withDatabaseContext { database ->
    ComparingPictures.TEST_TOLERANCE = 1
    ImageHashConnector.TEST_PIXEL_DISTANCE = 1
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