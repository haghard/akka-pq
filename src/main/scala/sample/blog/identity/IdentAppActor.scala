package sample.blog.identity

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorKilledException, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy }

object IdentAppActor {
  def props = Props(new IdentAppActor)
}

class IdentAppActor extends Actor with ActorLogging {

  val worker = context.actorOf(MessageProcessor.props, "processor")
  context.actorOf(KafkaMock.props(worker), "kafka")

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries  = 1 << 9, withinTimeRange = 15.seconds) {
    case _: ActorKilledException ⇒
      SupervisorStrategy.Restart
    case ex: Exception ⇒
      //log.error(ex, "Error:")
      SupervisorStrategy.Restart
    case other ⇒
      log.error(other, "Unexpected other: ")
      SupervisorStrategy.Stop
  }

  override def receive: Receive = Actor.ignoringBehavior
}
