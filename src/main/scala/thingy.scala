import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.serializer.{KryoSerializer}
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.{KryoSerializer, KryoRegistrator}
import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.apache.spark.sql.SQLContext
import com.databricks.spark.avro._

case class LogRecord( clientIp: String, clientIdentity: String, user: String, dateTime: String, request:String,statusCode:Int, bytesSent:Long, referer:String, userAgent:String )

object Logs {
  val PATTERN = """^(\S+) (\S+) (\S+) \[([\w:/]+\s[+\-]\d{4})\] "(\S+) (\S+) (\S+)" (\d{3}) (\S+) "(\S+)" "([^"]*)"""".r

  def parseLogLine(log: String): LogRecord = {
    try {
      val res = PATTERN.findFirstMatchIn(log)

      if (res.isEmpty) {
        println("Rejected Log Line: " + log)
        LogRecord("Empty", "-", "-", "", "",  -1, -1, "-", "-" )
      }
      else {
        val m = res.get
        // NOTE:   HEAD does not have a content size.
        if (m.group(9).equals("-")) {
          LogRecord(m.group(1), m.group(2), m.group(3), m.group(4),
            m.group(5), m.group(8).toInt, 0, m.group(10), m.group(11))
        }
        else {
          LogRecord(m.group(1), m.group(2), m.group(3), m.group(4),
            m.group(5), m.group(8).toInt, m.group(9).toLong, m.group(10), m.group(11))
        }
      }
    } catch
      {
        case e: Exception =>
          println("Exception on line:" + log + ":" + e.getMessage)
          LogRecord("Empty", "-", "-", "", "-", -1, -1, "-", "-" )
      }
  }

  //// Main Spark Program
  def main(args: Array[String]) {
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.apache.spark.storage.BlockManager").setLevel(Level.ERROR)
    Logger.getLogger("com.hortonworks.spark.Logs").setLevel(Level.INFO)

    val log = Logger.getLogger("com.hortonworks.spark.Logs")
    log.info("Started Logs Analysis")

    val sparkConf = new SparkConf().setAppName("Logs")

    sparkConf.set("spark.cores.max", "16")
    sparkConf.set("spark.serializer", classOf[KryoSerializer].getName)
    sparkConf.set("spark.sql.tungsten.enabled", "true")
    sparkConf.set("spark.eventLog.enabled", "true")
    sparkConf.set("spark.app.id", "Logs")
    sparkConf.set("spark.io.compression.codec", "snappy")
    sparkConf.set("spark.rdd.compress", "false")
    sparkConf.set("spark.suffle.compress", "true")

    val sc = new SparkContext(sparkConf)
    val logFile = sc.textFile("data/access3.log")
    //!_.clientIp.equals("Empty")
    val accessLogs = logFile.map(parseLogLine).filter( it => !it.clientIp.equals('Empty') )
    accessLogs.groupBy(_.statusCode).aggregate()

    log.info("# of Partitions %s".format(accessLogs.partitions.size))

    try {
      println("===== Log Count: %s".format(accessLogs.count()))
      accessLogs.take(5).foreach(println)

      try {
        val sqlContext = new SQLContext(sc)
        import sqlContext.implicits._

        val df1 = accessLogs.toDF()
        df1.registerTempTable("accessLogsDF")
        df1.printSchema()
        df1.describe("bytesSent").show()
        df1.first()
        df1.head()
        df1.explain()

        df1.write.format("avro").mode(org.apache.spark.sql.SaveMode.Append).partitionBy("statusCode").avro("avroresults")
      } catch {
        case e: Exception =>
          log.error("Writing files after job. Exception:" + e.getMessage)
          e.printStackTrace()
      }

      // Calculate statistics based on the content size.
      val contentSizes = accessLogs.map(log => log.bytesSent)
      val contentTotal = contentSizes.reduce(_ + _)

      println("===== Number of Log Records: %s  Content Size Total: %s, Avg: %s, Min: %s, Max: %s".format(
        contentSizes.count,
        contentTotal,
        contentTotal / contentSizes.count,
        contentSizes.min,
        contentSizes.max))

      sc.stop()
    }
  }