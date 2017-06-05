package sample.blog

import java.net.InetSocketAddress
import java.util.UUID

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.stream.scaladsl.{GraphDSL, Keep, Sink, Source, ZipWith}
import akka.stream._
import com.datastax.driver.core.{Cluster, Session}
import com.typesafe.config.ConfigFactory
import sample.blog.PsJournal.LastSeenException

import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext}
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

      //val cassandraMetricsRegistry = CassandraMetricsRegistry.get(system)
      //cassandraMetricsRegistry.getRegistry.getHistograms

      implicit val m = akka.stream.ActorMaterializer(ActorMaterializerSettings(system)
        .withDispatcher("cassandra-dispatcher")
        .withInputBuffer(pageSize, pageSize))

      implicit val e = m.executionContext

      /*
        val journal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
        val flow =
          journal.currentEventsByPersistenceId("", 0, Int.MaxValue)
            .toMat(Sink.foreach { _ =>
              println("")
            })(Keep.right)

        val f: Future[Done] = flow.run()
      */

      import scala.collection.JavaConverters._
      val contactPoints = config.getStringList("cassandra-journal.contact-points").asScala.toList
      val port0 = config.getInt("cassandra-journal.port")
      val cp = buildContactPoints(contactPoints, port0)
      val keySpace = config.getString("cassandra-journal.keyspace")

      def client = com.datastax.driver.core.Cluster.builder
        .addContactPointsWithPorts(cp.asJava)
        .build
        //.connect(keySpace)


      import scala.concurrent.duration._
      changes(client, keySpace, table, Roland, 0l, system.log, partitionSize, pageSize, 10.seconds)

/*

      journal(session, table, Roland, 0l, system.log, partitionSize, pageSize).run()
        .flatMap {
          journal(session, table, rec.persistence_id, rec.sequence_nr + 1, system.log, partitionSize, pageSize).run()
        }

        .onComplete {
        case Success(r) =>
          r.fold(stop(system)) { rec =>
            println("Last seen1 : " + r)
            Thread.sleep(20000)
            

            journal(session, table, rec.persistence_id, rec.sequence_nr + 1, system.log, partitionSize, pageSize).run().onComplete {
              case Success(r) =>
                println("Last seen2 : " + r)
                stop(system)
              case Failure(ex) =>
                stop(system)
            }
          }
        case Failure(ex) =>
          println("Failure 2")
          stop(system)
      }
*/

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

  def journal(client: Cluster, keySpace: String, table: String, pId: String, offset: Long, log: LoggingAdapter,
    partitionSize: Long, pageSize: Int) = {
    import scala.concurrent.duration._
    PsJournal[Record](client, keySpace, table, pId, offset, log, partitionSize, pageSize)
      .throttle(15, 1.second, 15, ThrottleMode.Shaping)
      .toMat(Sink.foreach { r => r.foreach { obj => println(obj.sequence_nr) } })(Keep.left)
  }

  def changes(client: Cluster, keySpace: String, table: String, pId: String, offset: Long, log: LoggingAdapter, partitionSize: Long,
    pageSize: Int, interval: FiniteDuration)(implicit system: ActorSystem, M: ActorMaterializer): Unit = {
      journal(client, keySpace, table, pId, offset, log, partitionSize, pageSize)
        .run()
        .onComplete {
          case Success(last) =>
            var nextOffset = 0l
            last match {
              case Left(ex) =>
                println("Error: " + ex.last)
                nextOffset = ex.last.fold(offset)(_.get.sequence_nr + 1)
              case Right(l) =>
                println("Page")
                nextOffset = l.fold(offset)(_.get.sequence_nr + 1)
            }
            system.scheduler.scheduleOnce(interval, new Runnable {
              override def run = {
                println(s"Next: $nextOffset")
                changes(client, keySpace, table, pId, nextOffset, log, partitionSize, pageSize, interval)
              }
            })(M.executionContext)

          case Failure(ex) =>
            println(s"LastSeenException")
            System.exit(-1)
        }(M.executionContext)
    }
}


/*startupSharedJournal(system, startStore = (port == "2551"), path =
  ActorPath.fromString("akka.tcp://ClusterSystem@127.0.0.1:2551/user/store"))
*/

/*
    def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath): Unit = {
      // Start the shared journal one one node (don't crash this SPOF)
      // This will not be needed with a distributed journal
      if (startStore)
        system.actorOf(Props[SharedLeveldbStore], "store")
      // register the shared journal
      import system.dispatcher
      implicit val timeout = Timeout(15.seconds)
      val f = (system.actorSelection(path) ? Identify(None))
      f.onSuccess {
        case ActorIdentity(_, Some(ref)) => SharedLeveldbJournal.setStore(ref, system)
        case _ =>
          system.log.error("Shared journal not started at {}", path)
          system.terminate()
      }
      f.onFailure {
        case _ =>
          system.log.error("Lookup of shared journal at {} timed out", path)
          system.terminate()
      }
     */