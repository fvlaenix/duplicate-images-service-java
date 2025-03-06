package duplicate.utils

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import java.io.InputStream
import java.sql.Blob
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.rowset.serial.SerialBlob

object LongBlobUtils {
  fun Table.longBlob(name: String): Column<Blob> = registerColumn(name, LongBlobColumnType())

  class LongBlobColumnType : ColumnType() {
    override fun sqlType(): String = "LONGBLOB"

    override fun nonNullValueToString(value: Any): String = "?"

    override fun readObject(rs: ResultSet, index: Int): Any? {
      return rs.getBytes(index)?.let { SerialBlob(it) }
    }

    override fun valueFromDB(value: Any): Any = when (value) {
      is Blob -> value
      is InputStream -> SerialBlob(value.readBytes())
      is ByteArray -> SerialBlob(value)
      else -> error("Unknown type for blob column :${value::class}")
    }

    override fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
      when (value) {
        is InputStream -> stmt.setBinaryStream(index, value, value.available())
        null -> stmt.setNull(index, Types.LONGVARBINARY)
        else -> super.setParameter(stmt, index, value)
      }
    }

    override fun notNullValueToDB(value: Any): Any {
      return (value as? Blob)?.binaryStream ?: value
    }

  }
}