package sample.blog.processes

import Table3._
import akka.actor.{ ActorLogging, Props }
import akka.persistence.PersistentActor

/**
 * https://youtu.be/LXEhQPEupX8?t=3009
 * https://github.com/adamw/reactive-akka-pres
 *
 * https://doc.akka.io/docs/akka/2.5.32/stream/stream-integrations.html
 * https://github.com/adamw/reactive-akka-pres/blob/master/src/main/scala/com/softwaremill/reactive/step3/LargestDelayActorStep3.scala
 *
 */
object Table3 {
  sealed trait Command
  final case class Add(id: Long) extends Command

  sealed trait Event
  final case class Added(id: Long) extends Event

  def props() = Props(new Table3)

}

class Table3 extends PersistentActor with ActorLogging {

  override val persistenceId: String = "aadffee"

  var inFlight = 0
  var backpressured = false

  val bufferSize = 1 << 3

  //it has at most 10 in flight
  def receiveCommand = {
    case cmd: Add ⇒
      if (inFlight < bufferSize) {
        //confirm|pull
        inFlight += 1
        //Put validation|log-running computation here

        //Underlying buffer also has at most 10 elements in flight
        persistAsync(Added(cmd.id)) { event ⇒
          inFlight -= 1
          //if(backpressured) confirm|pull
        }
      } else {
        backpressured = true
        //or back off
      }
  }

  override def receiveRecover: Receive = ???
}
