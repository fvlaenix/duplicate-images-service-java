package com.fvlaenix

import com.fvlaenix.duplicate.DuplicateImagesServer
import com.fvlaenix.duplicate.database.Connector
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger

const val LOGGING_PATH = "/logging.properties"

class RunServer

fun main() {
  try {
    LogManager.getLogManager().readConfiguration(RunServer::class.java.getResourceAsStream(LOGGING_PATH))
  } catch (e: Exception) {
    throw IllegalStateException("Failed while trying to read logs", e)
  }
  val runServerLog = Logger.getLogger(RunServer::class.java.name)
  val connection = Connector.connection
  runServerLog.log(Level.INFO, "Starting server")
  val server = DuplicateImagesServer(
    port = 50055,
    database = connection
  )
  server.start()
  runServerLog.log(Level.INFO, "Started server")
  server.blockUntilShutdown()
}