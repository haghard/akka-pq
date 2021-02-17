package sample.blog.processes

import akka.actor.ActorSystem
import akka.remote.ContainerFormats.ActorInitializationException
import akka.stream.typed.scaladsl.ActorSink
import com.typesafe.config.ConfigFactory

//runMain sample.blog.processes.TableApp
//select persistence_id, partition_nr, sequence_nr, timestamp, ser_id, ser_manifest from demo_journal where persistence_id='table-0' and partition_nr = 0;

/*
  CREATE TABLE demo_journal (
  persistence_id text,
  partition_nr bigint,
  sequence_nr bigint,
  timestamp timeuuid,
  timebucket text,
  event blob,
  event_manifest text,
  message blob,
  meta blob,
  meta_ser_id int,
  meta_ser_manifest text,
  ser_id int,
  ser_manifest text,
  tags set<text>,
  used boolean static,
  writer_uuid text,
  PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp, timebucket)
  ) WITH CLUSTERING ORDER BY (sequence_nr ASC, timestamp ASC, timebucket ASC)
*/

/*
CREATE TABLE chat_journal (
    persistence_id text,
    partition_nr bigint,
    sequence_nr bigint,
    timestamp timeuuid,
    event blob,
    event_manifest text,
    meta blob,
    meta_ser_id int,
    meta_ser_manifest text,
    ser_id int,
    ser_manifest text,
    tags set<text>,
    timebucket text,
    writer_uuid text,
    PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH CLUSTERING ORDER BY (sequence_nr ASC, timestamp ASC)
*/

object TableApp {

  //TODO: Try to throw an ActorInitializationException to stop sharded entity

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()

    val actorSystem = ActorSystem("table", config)

    import akka.actor.typed.scaladsl.adapter._
    val writer = actorSystem.actorOf(Table4.props()).toTyped[Table4.Protocol]

    val paSink =
      ActorSink
        .actorRefWithBackpressure[Table4.Command, Table4.Protocol, Table4.Ack](
          writer,
          Table4.Next(_, _),
          Table4.Init(_),
          Table4.Ack,
          Table4.SinkCompleted,
          Table4.Failed(_))

    ActorSystem("table", config).actorOf(TableAppActor.props(1), "gt-app")

    val cfg = ConfigFactory.parseString("akka.remote.artery.canonical.port=2552").withFallback(ConfigFactory.load())
    ActorSystem("table", cfg).actorOf(TableAppActor.props(2), "gt-app")

    import scala.concurrent.ExecutionContext.Implicits.global
    // If you’ve created a Promise and
    val p = scala.concurrent.Promise[Unit]()
    //you’ve handed out a future to that promise downstream
    val f = p.future

    val _ = f.map { _ ⇒
      var i = 0
      while (i < 10) {
        i += 1
        Thread.sleep(1000)
        println((" ★ " * i).mkString)
      }
      println("exit")
      ()
    }

    //.flatMap(???).filter(???)
    p.success(())
  }
}
