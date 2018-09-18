package sample.blog.identity

import scala.concurrent.duration._
import sample.blog.identity.IdentityMatcher.Confirm
import akka.actor.{Actor, ActorLogging, ActorRef, Props}

object IdentityBot {
  def props(region: ActorRef) = Props(new IdentityBot(region))
}

class IdentityBot(region: ActorRef) extends Actor with ActorLogging {
  var currentId = 501l

  region ! currentId

  override def receive = awaitFor(currentId)

  context.setReceiveTimeout(10.second)

  def awaitFor(id0 : Long): Receive = {
    case Confirm(id, true) if(id < id0) =>
      //ignore duplicate conf
    case Confirm(id, _) if(id == id0) =>
      log.info("********* Confirm {}  *********", id)
      currentId = currentId + 1l
      Thread.sleep(100)
      region ! currentId
      context.become(awaitFor(currentId))
    case akka.actor.ReceiveTimeout =>
      log.info("********* ReceiveTimeout: {} *********", currentId)
      import context.dispatcher
      context.system.scheduler.scheduleOnce(2.second)(region ! currentId)
    case other =>
      log.error("Other {} in Bot  ", other)
  }
}