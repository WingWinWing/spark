/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util

import java.io.{FileOutputStream, BufferedOutputStream, PrintWriter, IOException}
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FSDataOutputStream, Path}

import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.io.CompressionCodec

/**
 * A generic class for logging information to file.
 *
 * @param logDir Path to the directory in which files are logged
 * @param outputBufferSize The buffer size to use when writing to an output stream in bytes
 * @param compress Whether to compress output
 * @param overwrite Whether to overwrite existing files
 */
private[spark] class FileLogger(
    logDir: String,
    conf: SparkConf,
    hadoopConfiguration: Configuration,
    outputBufferSize: Int = 8 * 1024, // 8 KB
    compress: Boolean = false,
    overwrite: Boolean = true)
  extends Logging {

  private val dateFormat = new ThreadLocal[SimpleDateFormat]() {
    override def initialValue(): SimpleDateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
  }

  private val fileSystem = Utils.getHadoopFileSystem(new URI(logDir))
  var fileIndex = 0

  // Only used if compression is enabled
  private lazy val compressionCodec = CompressionCodec.createCodec(conf)

  // Only defined if the file system scheme is not local
  private var hadoopDataStream: Option[FSDataOutputStream] = None

  private var writer: Option[PrintWriter] = None

  createLogDir()

  /**
   * Create a logging directory with the given path.
   */
  private def createLogDir() {
    val path = new Path(logDir)
    if (fileSystem.exists(path)) {
      if (overwrite) {
        logWarning("Log directory %s already exists. Overwriting...".format(logDir))
        // Second parameter is whether to delete recursively
        fileSystem.delete(path, true)
      } else {
        throw new IOException("Log directory %s already exists!".format(logDir))
      }
    }
    if (!fileSystem.mkdirs(path)) {
      throw new IOException("Error in creating log directory: %s".format(logDir))
    }
  }

  /**
   * Create a new writer for the file identified by the given path.
   */
  private def createWriter(fileName: String): PrintWriter = {
    val logPath = logDir + "/" + fileName
    val uri = new URI(logPath)
    val defaultFs = FileSystem.getDefaultUri(hadoopConfiguration).getScheme
    val isDefaultLocal = (defaultFs == null || defaultFs == "file")

    /* The Hadoop LocalFileSystem (r1.0.4) has known issues with syncing (HADOOP-7844).
     * Therefore, for local files, use FileOutputStream instead. */
    val dstream =
      if ((isDefaultLocal && uri.getScheme == null) || uri.getScheme == "file") {
        // Second parameter is whether to append
        new FileOutputStream(uri.getPath, !overwrite)
      } else {
        val path = new Path(logPath)
        hadoopDataStream = Some(fileSystem.create(path, overwrite))
        hadoopDataStream.get
      }

    val bstream = new BufferedOutputStream(dstream, outputBufferSize)
    val cstream = if (compress) compressionCodec.compressedOutputStream(bstream) else bstream
    new PrintWriter(cstream)
  }

  /**
   * Log the message to the given writer.
   * @param msg The message to be logged
   * @param withTime Whether to prepend message with a timestamp
   */
  def log(msg: String, withTime: Boolean = false) {
    val writeInfo = if (!withTime) {
      msg
    } else {
      val date = new Date(System.currentTimeMillis())
      dateFormat.get.format(date) + ": " + msg
    }
    writer.foreach(_.print(writeInfo))
  }

  /**
   * Log the message to the given writer as a new line.
   * @param msg The message to be logged
   * @param withTime Whether to prepend message with a timestamp
   */
  def logLine(msg: String, withTime: Boolean = false) = log(msg + "\n", withTime)

  /**
   * Flush the writer to disk manually.
   *
   * If the Hadoop FileSystem is used, the underlying FSDataOutputStream (r1.0.4) must be
   * sync()'ed manually as it does not support flush(), which is invoked by when higher
   * level streams are flushed.
   */
  def flush() {
    writer.foreach(_.flush())
    hadoopDataStream.foreach(_.sync())
  }

  /**
   * Close the writer. Any subsequent calls to log or flush will have no effect.
   */
  def close() {
    writer.foreach(_.close())
    writer = None
  }

  /**
   * Start a writer for a new file, closing the existing one if it exists.
   * @param fileName Name of the new file, defaulting to the file index if not provided.
   */
  def newFile(fileName: String = "") {
    fileIndex += 1
    writer.foreach(_.close())
    val name = fileName match {
      case "" => fileIndex.toString
      case _ => fileName
    }
    writer = Some(createWriter(name))
  }

  /**
   * Close all open writers, streams, and file systems. Any subsequent uses of this FileLogger
   * instance will throw exceptions.
   */
  def stop() {
    hadoopDataStream.foreach(_.close())
    writer.foreach(_.close())
    fileSystem.close()
  }
}
