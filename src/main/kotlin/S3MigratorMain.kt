import duplicate.database.Connector
import duplicate.s3.S3Migrator
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * Main class for launching the image migration to S3
 */
class S3MigratorMain

fun main(args: Array<String>) {
  // Get optional command line parameters
  val batchSize = if (args.isNotEmpty()) args[0].toIntOrNull() ?: 100 else 100
  val parallelism = if (args.size > 1) args[1].toIntOrNull() ?: 16 else 16

  try {
    // Configure logging
    LogManager.getLogManager().readConfiguration(
      S3MigratorMain::class.java.getResourceAsStream("/logging.properties")
    )
  } catch (e: Exception) {
    System.err.println("Error configuring logging: ${e.message}")
    e.printStackTrace()
  }

  val logger = Logger.getLogger(S3MigratorMain::class.java.name)

  try {
    logger.info("Starting image migration to S3")
    logger.info("Parameters: batch size = $batchSize, parallelism = $parallelism")

    // Get database connection
    val database = Connector.connection

    // Create and run migrator
    val migrator = S3Migrator.create(database)
    migrator.migrateImagesToS3(batchSize, parallelism)

    logger.info("Migration successfully completed")
  } catch (e: Exception) {
    logger.log(Level.SEVERE, "Critical error during migration", e)
    System.err.println("Critical error during migration: ${e.message}")
    e.printStackTrace()
    exitProcess(1)
  }
}