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

package org.apache.spark.io

import java.io.{IOException, InputStream, OutputStream}

import com.ning.compress.lzf.{LZFInputStream, LZFOutputStream}
import net.jpountz.lz4.{LZ4BlockInputStream, LZ4BlockOutputStream}
import org.xerial.snappy.{Snappy, SnappyInputStream, SnappyOutputStream}

import org.apache.spark.SparkConf
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.Utils

/**
 * :: DeveloperApi ::
 * CompressionCodec allows the customization of choosing different compression implementations
 * to be used in block storage.
  * CompressionCodec允许定制在块存储中使用不同的压缩实现
 *
 * Note: The wire protocol for a codec is not guaranteed compatible across versions of Spark.
 *       This is intended for use as an internal compression utility within a single
 *       Spark application.
  *       注意：编码解码器的有线协议在Spark版本之间不能保证兼容,此功能旨在用作单个Spark应用程序中的内部压缩实用程序
 */
@DeveloperApi
trait CompressionCodec {

  def compressedOutputStream(s: OutputStream): OutputStream

  def compressedInputStream(s: InputStream): InputStream
}
/**
 * 为了节省磁盘存储空间,有些情况下需要对Block进行压缩
 */

private[spark] object CompressionCodec {
//用于压缩内部数据如 RDD分区和shuffle输出的编码解码器
  private val configKey = "spark.io.compression.codec"
  private val shortCompressionCodecNames = Map(
    "lz4" -> classOf[LZ4CompressionCodec].getName,
    "lzf" -> classOf[LZFCompressionCodec].getName,
    "snappy" -> classOf[SnappyCompressionCodec].getName)
   //默认压缩算法snappy,此压缩算法在牺牲少量压缩比例条件下,却极大地提高压缩速度
  def getCodecName(conf: SparkConf): String = {
    conf.get(configKey, DEFAULT_COMPRESSION_CODEC)
  }

  def createCodec(conf: SparkConf): CompressionCodec = {
    createCodec(conf, getCodecName(conf))
  }

  def createCodec(conf: SparkConf, codecName: String): CompressionCodec = {
    val codecClass = shortCompressionCodecNames.getOrElse(codecName.toLowerCase, codecName)
    val codec = try {
      val ctor = Utils.classForName(codecClass).getConstructor(classOf[SparkConf])
      Some(ctor.newInstance(conf).asInstanceOf[CompressionCodec])
    } catch {
      case e: ClassNotFoundException => None
      case e: IllegalArgumentException => None
    }
    codec.getOrElse(throw new IllegalArgumentException(s"Codec [$codecName] is not available. " +
      s"Consider setting $configKey=$FALLBACK_COMPRESSION_CODEC"))
  }

  /**
   * Return the short version of the given codec name.
   * If it is already a short name, just return it.
    * 返回给定编解码器名称的短版本,如果它已经是一个简短的名字,只要返回,
   */
  def getShortName(codecName: String): String = {
    if (shortCompressionCodecNames.contains(codecName)) {
      codecName
    } else {
      shortCompressionCodecNames
        .collectFirst { case (k, v) if v == codecName => k }
        .getOrElse { throw new IllegalArgumentException(s"No short name for codec $codecName.") }
    }
  }

  val FALLBACK_COMPRESSION_CODEC = "lzf"
  val DEFAULT_COMPRESSION_CODEC = "snappy"
  val ALL_COMPRESSION_CODECS = shortCompressionCodecNames.values.toSeq
}


/**
 * :: DeveloperApi ::
 * LZ4 implementation of [[org.apache.spark.io.CompressionCodec]].
 * Block size can be configured by `spark.io.compression.lz4.blockSize`.
 *
 * Note: The wire protocol for this codec is not guaranteed to be compatible across versions
 *       of Spark. This is intended for use as an internal compression utility within a single Spark
 *       application.
  *       注意：这种编解码器的有线协议不能保证在版本的Spark之间兼容。 这适用于单个Spark应用程序中的内部压缩实用程序。
 */
@DeveloperApi
class LZ4CompressionCodec(conf: SparkConf) extends CompressionCodec {

  override def compressedOutputStream(s: OutputStream): OutputStream = {
    val blockSize = conf.getSizeAsBytes("spark.io.compression.lz4.blockSize", "32k").toInt
    new LZ4BlockOutputStream(s, blockSize)
  }

  override def compressedInputStream(s: InputStream): InputStream = new LZ4BlockInputStream(s)
}


/**
 * :: DeveloperApi ::
 * LZF implementation of [[org.apache.spark.io.CompressionCodec]].
 *
 * Note: The wire protocol for this codec is not guaranteed to be compatible across versions
 *       of Spark. This is intended for use as an internal compression utility within a single Spark
 *       application.
  *       注意：这种编解码器的有线协议不能保证在版本的Spark之间兼容,这适用于单个Spark应用程序中的内部压缩实用程序。
 */
@DeveloperApi
class LZFCompressionCodec(conf: SparkConf) extends CompressionCodec {

  override def compressedOutputStream(s: OutputStream): OutputStream = {
    new LZFOutputStream(s).setFinishBlockOnFlush(true)
  }

  override def compressedInputStream(s: InputStream): InputStream = new LZFInputStream(s)
}


/**
 * :: DeveloperApi ::
 * Snappy implementation of [[org.apache.spark.io.CompressionCodec]].
 * Block size can be configured by `spark.io.compression.snappy.blockSize`.
  * [[org.apache.spark.io.CompressionCodec]]的Snappy实现]块大小可以由`spark.io.compression.snappy.blockSize`配置
 *
 * Note: The wire protocol for this codec is not guaranteed to be compatible across versions
 *       of Spark. This is intended for use as an internal compression utility within a single Spark
 *       application.
  *       注意：这种编解码器的有线协议不能保证在版本的Spark之间兼容,这适用于单个Spark应用程序中的内部压缩实用程序,
 */
@DeveloperApi
class SnappyCompressionCodec(conf: SparkConf) extends CompressionCodec {

  try {
    Snappy.getNativeLibraryVersion
  } catch {
    case e: Error => throw new IllegalArgumentException(e)
  }

  override def compressedOutputStream(s: OutputStream): OutputStream = {
    val blockSize = conf.getSizeAsBytes("spark.io.compression.snappy.blockSize", "32k").toInt
    new SnappyOutputStreamWrapper(new SnappyOutputStream(s, blockSize))
  }

  override def compressedInputStream(s: InputStream): InputStream = new SnappyInputStream(s)
}

/**
 * Wrapper over [[SnappyOutputStream]] which guards against write-after-close and double-close
 * issues. See SPARK-7660 for more details. This wrapping can be removed if we upgrade to a version
 * of snappy-java that contains the fix for https://github.com/xerial/snappy-java/issues/107.
 */
private final class SnappyOutputStreamWrapper(os: SnappyOutputStream) extends OutputStream {

  private[this] var closed: Boolean = false

  override def write(b: Int): Unit = {
    if (closed) {
      throw new IOException("Stream is closed")
    }
    os.write(b)
  }

  override def write(b: Array[Byte]): Unit = {
    if (closed) {
      throw new IOException("Stream is closed")
    }
    os.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    if (closed) {
      throw new IOException("Stream is closed")
    }
    os.write(b, off, len)
  }

  override def flush(): Unit = {
    if (closed) {
      throw new IOException("Stream is closed")
    }
    os.flush()
  }

  override def close(): Unit = {
    if (!closed) {
      closed = true
      os.close()
    }
  }
}
