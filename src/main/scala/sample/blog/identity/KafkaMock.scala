package sample.blog.identity

import scala.concurrent.duration._
import sample.blog.identity.MessageProcessor.Confirm
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }

object KafkaMock {

  def props(worker: ActorRef) =
    Props(new KafkaMock(worker))
}

/*

  If something breaks during our processing we will have never
  confirmed the message so it will be re delivered, we won't lose messages

 */
class KafkaMock(worker: ActorRef) extends Actor with ActorLogging {

  context.setReceiveTimeout(6.second)

  override def receive: Receive =
    active(6700L)

  def active(seqNum: Long): Receive = {
    case Confirm(id) ⇒
      if (id == seqNum) {
        //log.info("********* Confirmed {}  *********", id)
        Thread.sleep(100)
        val nextSeqNum = id + 1L
        worker ! nextSeqNum
        context become active(nextSeqNum)
      } else {
        //log.info("duplicate conf: [got:{} current{}]", id, seqNum)
        //ignore duplicate conf
      }
    case akka.actor.ReceiveTimeout ⇒
      log.info("********* Kafka re-delivers because no confirmation has been received: {}", seqNum)
      import context.dispatcher
      context.system.scheduler.scheduleOnce(1.second)(worker ! seqNum)
  }
}