package sample.blog.processes

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

//runMain sample.blog.processes.TableApp
object TableApp {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val system = ActorSystem("table", config)

    //select persistence_id, partition_nr, sequence_nr, timestamp, ser_id, ser_manifest from demo_journal where persistence_id='table-0' and partition_nr = 0;
    system.actorOf(TableAppActor.props, "gt-app")
  }
}

