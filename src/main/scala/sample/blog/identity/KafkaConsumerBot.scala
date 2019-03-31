package sample.blog.identity

import scala.concurrent.duration._
import sample.blog.identity.IdentityMatcher.Confirm
import akka.actor.{Actor, ActorLogging, ActorRef, Props}

object KafkaConsumerBot {
  def props(identActor: ActorRef) = Props(new KafkaConsumerBot(identActor))
}

class KafkaConsumerBot(identActor: ActorRef) extends Actor with ActorLogging {
  var currentId = 201l

  override def receive = awaitFor(currentId)

  context.setReceiveTimeout(20.second)

  def awaitFor(id0 : Long): Receive = {
    case Confirm(id, _) =>
      if(id == id0) {
        log.info("********* Kafka confirmed {}  *********", id)
        //currentId = currentId + 1l
        Thread.sleep(300)
        val nextId = id + 1l
        identActor ! nextId
        context.become(awaitFor(nextId))
      } else {
        //ignore duplicate conf
      }
    case akka.actor.ReceiveTimeout =>
      log.info("********* ReceiveTimeout: Resend:{} *********", id0)
      import context.dispatcher
      context.system.scheduler.scheduleOnce(2.second)(identActor ! id0)
  }
}