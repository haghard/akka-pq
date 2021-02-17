package sample.blog.processes

import Table4._
import akka.actor.typed.ActorRef
import akka.actor.{ ActorLogging, Props }
import akka.persistence.PersistentActor

object Table4 {

  sealed trait Protocol

  sealed trait Command

  final case class Add(id: Long) extends Command

  sealed trait Ack
  case object Ack extends Ack

  final case class Init(sink: ActorRef[Ack]) extends Protocol
  final case class Next(ackTo: ActorRef[Ack], cmd: Command) extends Protocol
  final case class Failed(cause: Throwable) extends Protocol
  case object SinkCompleted extends Protocol

  sealed trait Event

  final case class Added(id: Long) extends Event

  akka.stream.typed.scaladsl.ActorSink
    .actorRefWithBackpressure[Table4.Command, Table4.Protocol, Table4.Ack](
      ???,
      Table4.Next(_, _),
      Table4.Init(_),
      Table4.SinkCompleted,
      Table4.Failed(_))

  def props() = Props(new Table4)
}

/*
  Usage:
    akka.stream.typed.scaladsl.ActorSink
      .actorRefWithBackpressure[Table4.Command, Table4.Protocol, Table4.Ack](
        writer,
        Table4.Next(_, _),
        Table4.Init(_),
        Table4.Ack,
        Table4.SinkCompleted,
        Table4.SinkFailure(_))

    or

    akka.stream.typed.scaladsl.ActorSink
      .actorRefWithBackpressure[Table4.Command, Table4.Protocol, Table4.Ack](
        writer,
        Table4.Next(_, _),
        Table4.Init(_),
        Table4.SinkCompleted,
        Table4.Failed(_))

  Very similar to

  via(BackPressuredStage(1 << 2))

*/

//Underlying buffer for persistAsync also at most `watermark` elements in flight
class Table4 extends PersistentActor with ActorLogging {

  var inFlight = 0

  val watermark = 1 << 2

  var maybeLast: Option[Next] = None

  def enoughSpace: Boolean =
    inFlight < watermark

  //def isFull: Boolean = inFlight == watermark

  override val persistenceId: String = "aadffeeexcx"

  val behavior: Receive = {
    case Init(streamSender) ⇒
      streamSender.tell(Ack)

    case msg @ Next(streamSender, cmd) ⇒
      if (enoughSpace) {
        //command validation

        //Note: Demand isn't translated into one to one. It's batched up.
        streamSender.tell(Ack)
        inFlight += 1

        persistAsync(cmd) { _ ⇒

          //update internal state

          maybeLast match {
            case Some(backpressuredMsg) ⇒
              maybeLast = None
              //resent it back to me
              self ! backpressuredMsg
            case None ⇒
          }
          //if (isFull) streamSender.tell(Ack)

          inFlight -= 1
        }
      } else {
        maybeLast = Some(msg)
      }
    case SinkCompleted ⇒
      context.stop(self)
    case Failed(cause) ⇒
      log.error(s"Table has been stopped because of the error in the downstream", cause)
      context.stop(self)
  }

  def receiveCommand = behavior

  override def receiveRecover: Receive = ???
}
