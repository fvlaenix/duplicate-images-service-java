package com.fvlaenix.duplicate.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class DuplicateInfo(
  val group: String,
  val originalImageId: Long,
  val duplicateImageId: Long,
  val level: Long
)

object DuplicateInfoTable : Table() {
  val group = varchar("group", 100)
  val originalImageId = long("originalImageId")
  val duplicateImageId = long("duplicateImageId")
  
  val level = long("level")
}

class DuplicateInfoConnector(private val database: Database) {
  companion object {
    fun get(resultRow: ResultRow): DuplicateInfo = DuplicateInfo(
      resultRow[DuplicateInfoTable.group],
      resultRow[DuplicateInfoTable.originalImageId],
      resultRow[DuplicateInfoTable.duplicateImageId],
      resultRow[DuplicateInfoTable.level],
    )
  }
  
  init {
    transaction(database) {
      SchemaUtils.create(DuplicateInfoTable)
    }
  }
  
  fun add(
    group: String,
    originalImageId: Long,
    duplicateImageId: Long,
    level: Long
  ) = transaction(database) {
    val duplicateRequest = DuplicateInfoTable.select {
      (DuplicateInfoTable.originalImageId eq originalImageId) and
      (DuplicateInfoTable.duplicateImageId eq duplicateImageId)
    }
    if (duplicateRequest.count() > 0) false
    else {
      DuplicateInfoTable.insert {
        it[DuplicateInfoTable.group] = group
        it[DuplicateInfoTable.originalImageId] = originalImageId
        it[DuplicateInfoTable.duplicateImageId] = duplicateImageId
        it[DuplicateInfoTable.level] = level
      }
    }
  }
  
  fun removeById(
    imageId: Long
  ) = transaction(database) {
    DuplicateInfoTable.deleteWhere { (DuplicateInfoTable.originalImageId eq imageId) or (DuplicateInfoTable.duplicateImageId eq imageId) }
  }
  
  fun getAll(): List<DuplicateInfo> = transaction(database) {
    DuplicateInfoTable.selectAll().map { get(it) }
  }
}