package sample.blog.processes

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorKilledException, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy }

object TableAppActor {
  def props = Props(new TableAppActor)
}

class TableAppActor extends Actor with ActorLogging {

  val gt = context.actorOf(Table0.props, "gt")
  val source = context.actorOf(TableWriter.props(gt), "src")

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries  = 10, withinTimeRange = 15.seconds) {
    case _: ActorKilledException ⇒
      SupervisorStrategy.Restart
    case ex: Exception ⇒
      SupervisorStrategy.Restart
    case other ⇒
      log.error(other, "Unexpected other: ")
      SupervisorStrategy.Stop
  }

  override def receive: Receive = Actor.ignoringBehavior
}
