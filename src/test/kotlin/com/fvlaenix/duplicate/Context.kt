package com.fvlaenix.duplicate

import com.fvlaenix.duplicate.database.ImageOldConnector
import com.fvlaenix.duplicate.database.ImageOldTable
import com.fvlaenix.duplicate.image.ComparingMachine
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Properties

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
  
  fun <T> withImageContext(body: (ImageOldConnector) -> T): T = withDatabaseContext { database ->
    val connector = ImageOldConnector(database)
    return@withDatabaseContext try {
      body(connector)
    } finally {
      transaction(database) {
        SchemaUtils.drop(ImageOldTable)
      }
    }
  }
  
  fun <T> withComparingMachine(body: (ComparingMachine) -> T): T = withDatabaseContext { database ->
    val comparingMachine = ComparingMachine(database)
    return@withDatabaseContext try {
      body(comparingMachine)
    } finally {
      transaction(database) {
        SchemaUtils.drop(ImageOldTable)
      }
    }
  }
}