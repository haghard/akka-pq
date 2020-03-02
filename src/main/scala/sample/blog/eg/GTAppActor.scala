package sample.blog.eg

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorKilledException, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy }

object GTAppActor {
  def props = Props(new GTAppActor)
}

class GTAppActor extends Actor with ActorLogging {

  val gt = context.actorOf(Table0.props, "gt")
  val source = context.actorOf(GameTableWriter.props(gt), "src")

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries  = 10, withinTimeRange = 15.seconds) {
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
