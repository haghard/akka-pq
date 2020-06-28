package sample.blog

import akka.actor.{ ActorSystem, Props }
import com.typesafe.config.ConfigFactory
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }

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

//docker run -m 600M -it -p 7000:7000 -p 7001:7001 -p 9042:9042 -p 9160:9160 -p 7199:7199 -v /Volumes/dev/cassandra-db/blogs:/var/lib/cassandra -e JVM_OPTS="-Xms500M -Xmx500M" cassandra:3.11.2
//runMain sample.blog.BlogApp 2551
//runMain sample.blog.BlogApp 2552
//runMain sample.blog.BlogApp 2553
object BlogApp {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty) startup(Seq("2551", "2552", "0"))
    else startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports.foreach { port ⇒
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port)
        .withFallback(ConfigFactory.load())

      // Create an Akka system
      implicit val system = ActorSystem("blog", config)

      val authorListingRegion = ClusterSharding(system).start(
        typeName        = AuthorListing.shardName,
        entityProps     = AuthorListing.props(),
        settings        = ClusterShardingSettings(system).withRememberEntities(true),
        extractEntityId = AuthorListing.idExtractor,
        extractShardId  = AuthorListing.shardResolver)
      ClusterSharding(system).start(
        typeName        = Post.shardName,
        entityProps     = Post.props(authorListingRegion),
        settings        = ClusterShardingSettings(system).withRememberEntities(true),
        extractEntityId = Post.idExtractor,
        extractShardId  = Post.shardResolver)

      system.terminate()

      if (port != "2551" && port != "2552")
        system.actorOf(Props[Bot], "bot")
    }
  }
}
