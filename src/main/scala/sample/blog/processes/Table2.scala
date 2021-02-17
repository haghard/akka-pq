package sample.blog.processes

import akka.actor.ActorLogging
import akka.persistence.{ AtLeastOnceDelivery, PersistentActor }

object Table2 {

  sealed trait Command
  final case class Mark(id: Long) extends Command

  sealed trait Event
  case class WatermarkMark(id: Long) extends Event

  sealed trait Reply
  case class Marked(id: Long) extends Reply

}

class Table2 extends PersistentActor with AtLeastOnceDelivery with ActorLogging {

  override val persistenceId: String = "aadff"

  override def receiveRecover: Receive = ???

  //Why: to guarantee ordering between 2 async callbacks
  override def receiveCommand: Receive = {
    case Table2.Mark(id) ⇒
      //
      persistAsync(Table2.WatermarkMark(id)) { marker ⇒

        //update state ...
      }

      //executes once all persistAsync handlers done
      deferAsync(Table2.Marked(id)) { marked ⇒
        sender() ! marked
      }

    //ordering between persistAsync and defer is guaranteed
  }
}
