package sample.blog

import java.net.InetSocketAddress
import java.util.UUID
import akka.actor.{ Actor, ActorRef, ActorSystem, OneForOneStrategy, Props }
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.{ Keep, Sink }
import akka.stream._
import com.datastax.driver.core.{ Cluster, SocketOptions }
import com.typesafe.config.ConfigFactory
import sample.blog.PsJournal.LastSeen

import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.{ Failure, Success }

//7-Patrik
//28-Roland
//17-Martin
//94-Endre
//82-Bj%C3%B6rn

/*
  CREATE TABLE blogs.blogs_journal (
      persistence_id text,
      partition_nr bigint,
      sequence_nr bigint,
      timestamp timeuuid,
      timebucket text,
      event blob,
      event_manifest text,
      message blob,
      ser_id int,
      ser_manifest text,
      tag1 text,
      tag2 text,
      tag3 text,
      used boolean static,
      writer_uuid text,
      PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp, timebucket)
  ) WITH CLUSTERING ORDER BY (sequence_nr ASC, timestamp ASC, timebucket ASC)
*/
object BlogAppJournal {
  val Roland = "28-Roland"
  val Patrik = "7-Patrik"
  val Martin = "17-Martin"
  val Endre = "94-Endre"

  case class Record(persistence_id: String, partition_nr: Long = 0l, sequence_nr: Long = 1,
      timestamp: UUID, timebucket: String, event: Array[Byte])

  import shapeless.HList

  class RecordH[H <: HList](val hs: H)

  object RecordH {
    def apply[A](a: A) = new RecordH(a :: shapeless.HNil)

    def apply[A, B](a: A, b: B) = new RecordH(a :: b :: shapeless.HNil)

    def apply[A, B, C](a: A, b: B, c: C) = new RecordH(a :: b :: c :: shapeless.HNil)

    def apply[A, B, C, D](a: A, b: B, c: C, d: D) = new RecordH(a :: b :: c :: d :: shapeless.HNil)

    def apply[A, B, C, D, E](a: A, b: B, c: C, d: D, e: E) = new RecordH(a :: b :: c :: d :: e :: shapeless.HNil)

    def apply[A, B, C, D, E, F](a: A, b: B, c: C, d: D, e: E, f: F) = new RecordH(a :: b :: c :: d :: e :: f :: shapeless.HNil)

    import shapeless.Generic

    def apply[P <: Product, L <: HList](p: P)(implicit gen: Generic.Aux[P, L]) = new RecordH[L](gen to p)
  }

  val list = RecordH(1, "", true).hs
  list(0)
  list(1)
  list(2)
  //list(3)

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) startup(Seq("2551", "2552", "0"))
    else startup(args)
  }

  private def buildContactPoints(contactPoints: immutable.Seq[String], port: Int): immutable.Seq[InetSocketAddress] = {
    contactPoints match {
      case null | Nil ⇒ throw new IllegalArgumentException("A contact point list cannot be empty.")
      case hosts ⇒ hosts map {
        ipWithPort ⇒
          ipWithPort.split(":") match {
            case Array(host, port) ⇒ new InetSocketAddress(host, port.toInt)
            case Array(host)       ⇒ new InetSocketAddress(host, port)
            case msg               ⇒ throw new IllegalArgumentException(s"A contact point should have the form [host:port] or [host] but was: $msg.")
          }
      }
    }
  }

  def stop(system: ActorSystem) = {
    Await.result(system.terminate(), Duration.Inf)
    System.exit(0)
  }

  case class Tick()

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port ⇒
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port)
        .withFallback(ConfigFactory.load())

      // Create an Akka system
      implicit val system = ActorSystem("blog", config)

      //val cassandraMetricsRegistry = CassandraMetricsRegistry.get(system)
      //val histograms = cassandraMetricsRegistry.getRegistry.getHistograms

      val partitionSize = config.getInt("cassandra-journal.target-partition-size")
      val table = config.getString("cassandra-journal.table")
      val pageSize = partitionSize / 2

      implicit val m = akka.stream.ActorMaterializer(ActorMaterializerSettings(system)
        .withDispatcher("cassandra-dispatcher")
        .withInputBuffer(pageSize, pageSize))

      implicit val e = m.executionContext

      import scala.concurrent.duration._
      import scala.collection.JavaConverters._

      val contactPoints = config.getStringList("cassandra-journal.contact-points").asScala.toList
      val port0 = config.getInt("cassandra-journal.port")
      val cp = buildContactPoints(contactPoints, port0)
      val keySpace = config.getString("cassandra-journal.keyspace")

      val user = config.getString("cassandra-journal.authentication.username")
      val pws = config.getString("cassandra-journal.authentication.password")

      val client = com.datastax.driver.core.Cluster.builder
        .addContactPointsWithPorts(cp.asJava)
        .withCredentials(user, pws)
        //http://docs.datastax.com/en/developer/java-driver/3.2/manual/socket_options/
        .withSocketOptions(new SocketOptions().setConnectTimeoutMillis(5000))
        .build

      changes(client, keySpace, table, Roland, 0l, partitionSize, pageSize, 10.seconds)
      //changes2(Roland, 0l, 10.seconds)
    }
  }

  def journal(client: Cluster, keySpace: String, table: String, pId: String, offset: Long, partitionSize: Long, pageSize: Int) = {
    import scala.concurrent.duration._
    //PsJournal[Record](client, keySpace, table, pId, offset, partitionSize, pageSize)
    PsJournal.typedRow(client, keySpace, table, pId, offset, partitionSize, pageSize)
      .throttle(pageSize, 1.second, 15, ThrottleMode.Shaping)
      .toMat(Sink.foreach { r ⇒ println(r) })(Keep.left)
    //.toMat(Sink.foreach { r => r.foreach { obj => println(obj) } })(Keep.left)
  }

  def journalAkka(system: ActorSystem, pId: String, offset: Long) = {
    import scala.concurrent.duration._
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      .currentEventsByPersistenceId(Roland, offset, Int.MaxValue)
      .map(_.sequenceNr)
      .viaMat(new LastSeen)(Keep.right)
      .throttle(15, 1.second, 10, ThrottleMode.Shaping)
      .toMat(Sink.foreach { sequence_nr ⇒ println(sequence_nr) })(Keep.left)
  }

  def changes(client: Cluster, keySpace: String, table: String, pId: String, offset: Long, partitionSize: Long,
    pageSize: Int, interval: FiniteDuration)(implicit system: ActorSystem, M: ActorMaterializer): Unit = {
    journal(client, keySpace, table, pId, offset, partitionSize, pageSize)
      .run()
      .onComplete {
        case Success(last) ⇒
          //val nextOffset = last.fold(offset)(_.get.sequence_nr + 1)
          val nextOffset = last.fold(offset)(_._3 + 1l)
          system.scheduler.scheduleOnce(interval, new Runnable {
            override def run = {
              println(s"Trying to read starting from: $nextOffset")
              changes(client, keySpace, table, pId, nextOffset, partitionSize, pageSize, interval)
            }
          })(M.executionContext)
        case Failure(ex) ⇒
          println(s"Unexpected error:" + ex.getMessage)
          client.close()
          System.exit(-1)
      }(M.executionContext)
  }

  def changes2(pId: String, offset: Long, interval: FiniteDuration)(implicit system: ActorSystem, M: ActorMaterializer): Unit = {
    journalAkka(system, pId, offset)
      .run()
      .onComplete {
        case Success(last) ⇒
          val nextOffset = last.fold(offset)(_ + 1)
          system.scheduler.scheduleOnce(interval, new Runnable {
            override def run = {
              println(s"Trying to read starting from: $nextOffset")
              changes2(pId, nextOffset, interval)
            }
          })(M.executionContext)
        case Failure(ex) ⇒
          println(s"Unexpected error:" + ex.getMessage)
          System.exit(-1)
      }(M.executionContext)
  }
}