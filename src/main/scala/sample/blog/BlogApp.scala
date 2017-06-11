package sample.blog

import java.net.InetSocketAddress
import java.util.UUID

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.event.LoggingAdapter
import akka.persistence.cassandra.CassandraMetricsRegistry
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.{GraphDSL, Keep, Sink, Source, ZipWith}
import akka.stream._
import com.datastax.driver.core.{Cluster, Session, SocketOptions}
import com.typesafe.config.ConfigFactory
import sample.blog.PsJournal.LastSeen

import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}

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
//runMain sample.blog.BlogApp 2551
//runMain sample.blog.BlogApp 2552
//runMain sample.blog.BlogApp 2553
object BlogApp {
  val Roland = "28-Roland"
  val Patrik = "7-Patrik"
  val Martin = "17-Martin"
  val Endre = "94-Endre"

  case class Record(persistence_id: String, partition_nr: Long = 0l, sequence_nr: Long = 1,
    timestamp: UUID, timebucket: String, event: Array[Byte])

  import shapeless.HList

  class RecordH[H <: HList](hs: H)

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


  def main(args: Array[String]): Unit = {
    if (args.isEmpty) startup(Seq("2551", "2552", "0"))
    else startup(args)
  }

  private def buildContactPoints(contactPoints: immutable.Seq[String], port: Int): immutable.Seq[InetSocketAddress] = {
    contactPoints match {
      case null | Nil => throw new IllegalArgumentException("A contact point list cannot be empty.")
      case hosts => hosts map {
        ipWithPort =>
          ipWithPort.split(":") match {
            case Array(host, port) => new InetSocketAddress(host, port.toInt)
            case Array(host) => new InetSocketAddress(host, port)
            case msg => throw new IllegalArgumentException(s"A contact point should have the form [host:port] or [host] but was: $msg.")
          }
      }
    }
  }


  def stop(/*session: Session, cluster: Cluster,*/ system: ActorSystem) = {
    //session.close
    //cluster.close
    Await.result(system.terminate(), Duration.Inf)
    System.exit(0)
  }

  case class Tick()

  def startup(ports: Seq[String]): Unit = {

    ports foreach { port =>
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port)
        .withFallback(ConfigFactory.load())

      val partitionSize = config.getInt("cassandra-journal.target-partition-size")
      val table = config.getString("cassandra-journal.table")
      val ks = config.getString("cassandra-journal.keyspace")

      val pageSize = partitionSize / 2

      // Create an Akka system
      implicit val system = ActorSystem("ClusterSystem", config)

      val cassandraMetricsRegistry = CassandraMetricsRegistry.get(system)
      cassandraMetricsRegistry.getRegistry.getHistograms

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

      val client = com.datastax.driver.core.Cluster.builder
        .addContactPointsWithPorts(cp.asJava)
        //http://docs.datastax.com/en/developer/java-driver/3.2/manual/socket_options/
        .withSocketOptions(new SocketOptions().setConnectTimeoutMillis(2000))
        .build

      changes(client, keySpace, table, Roland, 0l, partitionSize, pageSize, 10.seconds)
      //changes2(Roland, 0l, 10.seconds)


/*
      val authorListingRegion = ClusterSharding(system).start(
        typeName = AuthorListing.shardName,
        entityProps = AuthorListing.props(),
        settings = ClusterShardingSettings(system).withRememberEntities(true),
        extractEntityId = AuthorListing.idExtractor,
        extractShardId = AuthorListing.shardResolver)
      ClusterSharding(system).start(
        typeName = Post.shardName,
        entityProps = Post.props(authorListingRegion),
        settings = ClusterShardingSettings(system).withRememberEntities(true),
        extractEntityId = Post.idExtractor,
        extractShardId = Post.shardResolver)

      if (port != "2551" && port != "2552")
        system.actorOf(Props[Bot], "bot")
      */
    }
  }

  def journal(client: Cluster, keySpace: String, table: String, pId: String, offset: Long, partitionSize: Long, pageSize: Int) = {
    import scala.concurrent.duration._
    PsJournal[Record](client, keySpace, table, pId, offset, partitionSize, pageSize)
      .throttle(15, 1.second, 15, ThrottleMode.Shaping)
      .toMat(Sink.foreach { r => r.foreach { obj => println(obj.sequence_nr) } })(Keep.left)
  }

  def journal2(system: ActorSystem, pId: String, offset: Long) = {
      import scala.concurrent.duration._
      PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
        .currentEventsByPersistenceId(Roland, offset, Int.MaxValue).map(_.sequenceNr)
        .viaMat(new LastSeen)(Keep.right)
        .throttle(15, 1.second, 10, ThrottleMode.Shaping)
        .toMat(Sink.foreach {  sequence_nr => println(sequence_nr) })(Keep.left)
    }

  def changes(client: Cluster, keySpace: String, table: String, pId: String, offset: Long, partitionSize: Long,
    pageSize: Int, interval: FiniteDuration)(implicit system: ActorSystem, M: ActorMaterializer): Unit = {
      journal(client, keySpace, table, pId, offset, partitionSize, pageSize)
        .run()
        .onComplete {
          case Success(last) =>
            val nextOffset = last.fold(offset)(_.get.sequence_nr + 1)
            system.scheduler.scheduleOnce(interval, new Runnable {
              override def run = {
                println(s"Trying to read starting from: $nextOffset")
                changes(client, keySpace, table, pId, nextOffset, partitionSize, pageSize, interval)
              }
            })(M.executionContext)
          case Failure(ex) =>
            println(s"Unexpected error:" + ex.getMessage)
            System.exit(-1)
        }(M.executionContext)
    }

  def changes2(pId: String, offset: Long, interval: FiniteDuration)
    (implicit system: ActorSystem, M: ActorMaterializer): Unit = {
        journal2(system, pId, offset)
          .run()
          .onComplete {
            case Success(last) =>
              val nextOffset = last.fold(offset)(_ + 1)
              system.scheduler.scheduleOnce(interval, new Runnable {
                override def run = {
                  println(s"Trying to read starting from: $nextOffset")
                  changes2(pId, nextOffset, interval)
                }
              })(M.executionContext)
            case Failure(ex) =>
              println(s"Unexpected error:" + ex.getMessage)
              System.exit(-1)
          }(M.executionContext)
      }
}