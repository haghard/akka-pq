package sample.blog

import akka.actor.{ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.stream.ActorMaterializerSettings
import com.typesafe.config.ConfigFactory

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
object BlogApp {
  val Roland = "28-Roland"
  val Patrik = "7-Patrik"
  val Martin = "17-Martin"
  val Endre = "94-Endre"

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) startup(Seq("2551", "2552", "0"))
    else startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port =>
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load())

      val partitionSize = config.getInt("cassandra-journal.target-partition-size")
      val table = config.getString("cassandra-journal.table")
      val ks = config.getString("cassandra-journal.keyspace")

      val pageSize = partitionSize / 2

      // Create an Akka system
      implicit val system = ActorSystem("ClusterSystem", config)

      implicit val m = akka.stream.ActorMaterializer(ActorMaterializerSettings(system)
        .withDispatcher("cassandra-dispatcher")
        .withInputBuffer(pageSize, pageSize))

      implicit val e = m.executionContext

      /*
      case class Record(persistence_id: String, partition_nr: Long, sequence_nr: Long,
        timestamp: UUID, timebucket: String, event: Array[Byte])

      val host = new util.ArrayList[InetSocketAddress]()
      host.add(new InetSocketAddress("78.155.219.224", 9042))

      val cluster = com.datastax.driver.core.Cluster.builder
        .addContactPointsWithPorts(host)
        .build

      val session = cluster.connect(ks)
      val c = new AtomicInteger()

      val f = PsJournal[Record](session, table, Roland, 0, system.log, partitionSize, pageSize)
        .log("Roland-ps")(system.log)
        .toMat(Sink.foreach { r =>
          c.incrementAndGet
          r.foreach { obj =>
            println(obj.event.length)
          }
        })(Keep.right)

      f.run().onComplete { _ =>
        println("*************** Count: " + c.get)
        session.close
        cluster.close
        Await.result(system.terminate(), Duration.Inf)
        System.exit(0)
      }
*/
      /*startupSharedJournal(system, startStore = (port == "2551"), path =
        ActorPath.fromString("akka.tcp://ClusterSystem@127.0.0.1:2551/user/store"))
      */

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

    }

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
  }
}

