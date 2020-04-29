package sample.blog.processes

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

//runMain sample.blog.eg.TableApp
object TableApp {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=2551")
      .withFallback(ConfigFactory.load())

    // Create an Akka system
    val system = ActorSystem("table", config)
    system.actorOf(TableAppActor.props, "gt-app")
  }
}

