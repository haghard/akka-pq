package sample.blog.monix

import java.net.InetAddress

import com.datastax.driver.core._
import java.lang.{ Long ⇒ JLong }
import java.time._
import java.util.{ TimeZone, UUID }
import java.util.concurrent.CountDownLatch

import akka.persistence.query.Offset
import com.datastax.driver.core.utils.UUIDs
import com.datastax.driver.extras.codecs.jdk8.ZonedDateTimeCodec

import scala.concurrent.{ Await, ExecutionContext, Future }
import monix.eval.Task
import monix.execution.Ack
import monix.reactive.Observable
import monix.execution.Scheduler.Implicits.global

import scala.util.{ Failure, Success }

//https://www.beyondthelines.net/databases/querying-cassandra-from-scala/
//https://monix.io/blog/2018/03/19/monix-v3.0.0-RC1.html
object PsJournal {

  def execute1(statement: Future[PreparedStatement], pId: String)(
    implicit
    executionContext: ExecutionContext, session: Session
  ): Future[ResultSet] = {
    statement
      .map(_.bind(pId).setFetchSize(1 << 5))
      .flatMap(session.executeAsync(_))
  }

  def execute2(statement: Future[PreparedStatement], pId: String, pNum: JLong)(
    implicit
    executionContext: ExecutionContext, session: Session
  ): Future[ResultSet] = {
    println(s"fetch $pId - $pNum")
    statement
      .map(_.bind(pId, pNum).setFetchSize(1 << 5))
      .flatMap(session.executeAsync(_))
  }

  def query(cql: Future[PreparedStatement], pId: String, pNum: JLong)(
    implicit
    executionContext: ExecutionContext, cassandraSession: Session
  ): Observable[Row] = {
    val obsPerPartition = Observable.fromAsyncStateAction[Future[ResultSet], ResultSet]({ nextRsF ⇒
      Task.fromFuture(nextRsF).flatMap { rs ⇒
        println("**** page ****")
        Task((rs, rs.fetchMoreResults))
      }
    })(execute2(cql, pId, pNum))

    obsPerPartition
      .takeWhile(!_.isExhausted)
      .flatMap { rs ⇒
        val available = rs.getAvailableWithoutFetching
        println(available)
        Observable.fromIterable(
          new Iterable[Row]() {
            val iterator: Iterator[Row] =
              Iterator.fill(available)(rs.one)
          }
        )
        //import scala.collection.JavaConverters._
        //Observable.fromIterator(rs.iterator().asScala)
      }
  }

  def queryF(cqlF: Future[PreparedStatement], pId: String /*, offset: String*/ )(
    implicit
    ec: ExecutionContext, c: Session
  ): Unit = {
    //val tz = TimeZone.getDefault.toZoneId
    def loop(partitionData: () ⇒ Future[ResultSet]): Unit = {
      partitionData().onComplete {
        case Success(rs) ⇒
          //println(Thread.currentThread.getName)
          if (rs.isExhausted) println("done")
          else {
            val available = rs.getAvailableWithoutFetching
            val page = IndexedSeq.fill(available)(rs.one)

            //TimeZone.getTimeZone()

            //val utc = ZoneOffset.UTC
            //val plus4 = ZoneId.of("Europe/Moscow")
            page.foreach { r ⇒
              val dt = r.getTupleValue("when")
              val ts = dt.getTimestamp(0)
              val tz = TimeZone.getTimeZone(dt.getString(1))
              val zoneDT = ts.toInstant.atZone(tz.toZoneId)

              //val a = OffsetDateTime.ofInstant(ts.toInstant, utc)

              //val zoneDT = ZonedDateTime.ofInstant(ts.toInstant, tz.toZoneId)
              println(s"$ts - ${zoneDT}")
            }

            /*page.foreach { r =>
              val whenUuid = r.getUUID("when")
              val when = Instant.ofEpochMilli(UUIDs.unixTimestamp(whenUuid)).atZone(tz)
              println(s"$when")
            }*/

            /*page.foreach { r =>
              val timeUuid = r.getUUID("when")
              //in UTC
              //val inst = Instant.ofEpochMilli(UUIDs.unixTimestamp(timeUuid))
              val inst = Instant.ofEpochMilli(UUIDs.unixTimestamp(timeUuid)).atZone(tz)
              println(s"$timeUuid - $inst")
            }*/

            println(page(page.size - 1))
            loop(() ⇒ asScalaFuture(rs.fetchMoreResults))
          }
        case Failure(ex) ⇒
          ex.printStackTrace()
      }
    }

    loop(
      () ⇒ cqlF
        .map(_.bind(pId /*UUID.fromString(offset)*/ ).setFetchSize(1 << 5))
        .flatMap(c.executeAsync(_))
    )
  }

  def main(args: Array[String]): Unit = {
    val l = new CountDownLatch(1)

    import monix.execution.Scheduler.Implicits.global

    //https://docs.datastax.com/en/developer/java-driver/3.4/manual/query_timestamps/
    val cluster = new Cluster.Builder()
      .addContactPoints(InetAddress.getByName("192.168.77.42"))
      //.withTimestampGenerator(new AtomicMonotonicTimestampGenerator())  //give us RYW consistency, latest versions of driver does it
      .withPort(9042)
      .build()

    //import com.datastax.driver.extras.codecs.jdk8.InstantCodec
    //cluster.getConfiguration().getCodecRegistry().register(InstantCodec.instance)

    /*
    One problem with timestamp is that it does not store time zones.
    ZonedDateTimeCodec addresses that, by mapping a ZonedDateTime to a tuple<timestamp,varchar>

    CREATE TABLE blogs.timelineTs (tl_name text, when tuple<timestamp,varchar>, login text, message text, PRIMARY KEY (tl_name, when))
      WITH CLUSTERING ORDER BY (when DESC);
    */

    val tupleType = cluster.getMetadata.newTupleType(DataType.timestamp(), DataType.varchar())
    cluster.getConfiguration().getCodecRegistry().register(new ZonedDateTimeCodec(tupleType))

    implicit val session = cluster.connect

    session.execute(
      "INSERT INTO blogs.timelineTs (tl_name, when, login, message) VALUES (?, ?, ?, ?)",
      ZonedDateTime.parse("2010-06-30T01:20:47.999+01:00"))

    /*queryF(
     cql"SELECT persistence_id, sequence_nr FROM blogs.blogs_journal where persistence_id = ? and partition_nr = ?",
     "7-Patrik", 0l)*/

    // creates an observable of row

    //executeH("twitter", 1)(statement.map(_.bind(_)))

    /*
    val f = cql"INSERT INTO blogs.timelineTs (tl_name, when, login, message) VALUES (?, ?, ?, ?)"
        .map(_.bind("tw", ZonedDateTime.now(), "haghard", "bla-bla1"))
        .flatMap(session.executeAsync(_))

    val f2 = cql"INSERT INTO blogs.timelineTs (tl_name, when, login, message) VALUES (?, ?, ?, ?)"
       .map(_.bind("tw", ZonedDateTime.now(ZoneOffset.ofHours(2)), "haghard", "bla-bla1"))
       .flatMap(session.executeAsync(_))

    import scala.concurrent.duration._
    Await.result(f, 3.seconds)
    Await.result(f2, 3.seconds)
    */

    queryF(cql"SELECT tl_name, when FROM blogs.timelineTs WHERE tl_name = ?", "tw")

    //queryF(cql"SELECT tl_name, when FROM blogs.timeline WHERE tl_name = ?", "twitter")

    //Instant.ofEpochMilli(UUIDs.unixTimestamp(timeUuid))

    /*
    queryF(cql"SELECT login, message, when FROM blogs.timeline WHERE tl_name = ? AND when > ?",
      "twitter", "612b0650-9016-11e8-a994-6d2c86545d91")
    */

    /*val obs = query(
      cql"SELECT persistence_id, sequence_nr, timestamp FROM blogs.blogs_journal where persistence_id = ? and partition_nr = ?",
      "7-Patrik", 0l)

    obs.subscribe({ row =>
      println(Offset.timeBasedUUID(row.getUUID("timestamp")))
      //println(row.getLong("sequence_nr"))
      Ack.Continue
    }, { e: Throwable =>
      e.printStackTrace
    }, { () =>
      println("done")
      l.countDown
    })*/

    l.await(5l, java.util.concurrent.TimeUnit.SECONDS)
    println("exit")
    System.exit(0)
  }
}