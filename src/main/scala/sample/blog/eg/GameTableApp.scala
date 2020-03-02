package sample.blog.eg

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

//runMain sample.blog.eg.GameTableApp
object GameTableApp {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + 2551)
      .withFallback(ConfigFactory.load())

    // Create an Akka system
    val system = ActorSystem("gt", config)
    system.actorOf(GTAppActor.props, "gt-app")
  }
}

