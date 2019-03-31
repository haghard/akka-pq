package sample.blog.identity

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}


//runMain sample.blog.identity.IdentityApp 2551
//runMain sample.blog.identity.IdentityApp 2553
object IdentityApp {

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) startup(Seq("2551", "2552", "0"))
    else startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports.foreach { port =>
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port)
        .withFallback(ConfigFactory.load())

      // Create an Akka system
      val system = ActorSystem("ident", config)

      /*val region =
        ClusterSharding(system).start(
          typeName = "Identity",
          entityProps = IdentityMatcher.props,
          settings = ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId = IdentityMatcher.idExtractor,
          extractShardId = IdentityMatcher.shardResolver)
      */

      if (port != "2551" && port != "2552") {
        val region = system.actorOf(IdentityMatcher.props, "matcher")
        system.actorOf(KafkaConsumerBot.props(region), "identity-bot")
      }
    }
  }
}
