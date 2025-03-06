package duplicate.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Properties
import java.util.logging.Logger
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

private val LOGGER = Logger.getLogger(Connector::class.simpleName)

private val ENV_PROPERTIES = System.getenv("PATH_TO_DATABASE_PROPERTIES")?.let {
  val path = Path(it)
  if (path.notExists()) {
    throw IllegalStateException("Path should be accessible: $path")
  }
  Properties().apply { load(path.inputStream()) }
}

private val RESERVE_PROPERTIES = Connector::class.java.getResourceAsStream("/database.properties")?.let {
  Properties().apply { load(it) }
}

private val PROPERTIES = ENV_PROPERTIES?.apply { LOGGER.info("Using env properties") } ?:
 RESERVE_PROPERTIES?.apply { LOGGER.info("Using git properties") } ?:
 throw IllegalStateException("Can't find env path ${System.getenv("PATH_TO_DATABASE_PROPERTIES")} and local path")

private val DATABASE_URL = PROPERTIES.getProperty("url") ?: throw IllegalStateException("url should exists in properties")
private val DATABASE_DRIVER = PROPERTIES.getProperty("driver") ?: throw IllegalStateException("driver should exists in properties")
private val DATABASE_USER = PROPERTIES.getProperty("user") ?: throw IllegalStateException("user should exists in properties")
private val DATABASE_PASSWORD = PROPERTIES.getProperty("password") ?: throw IllegalStateException("password should exists in properties")

object Connector {
  
  val connection = Database.connect(
    url = DATABASE_URL, driver = DATABASE_DRIVER, user = DATABASE_USER, password = DATABASE_PASSWORD
  )

  init {
    transaction(connection) {
      // create new tables
    }
  }
}