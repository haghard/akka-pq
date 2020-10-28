package sample.blog.processes

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorKilledException, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy }

import scala.util.control.NonFatal

object TableAppActor {

  def props(ind: Int) = Props(new TableAppActor(ind))
}

class TableAppActor(ind: Int) extends Actor with ActorLogging {

  val gt = context.actorOf(Table0.props(ind), "gt")
  val source = context.actorOf(TableWriter.props(gt), "src")

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries  = 10, withinTimeRange = 15.seconds) {
    case _: ActorKilledException ⇒
      SupervisorStrategy.Restart
    case NonFatal(err) ⇒
      log.error(err, "Unexpected other: ")
      SupervisorStrategy.Restart
  }

  override def receive: Receive = Actor.ignoringBehavior
}
