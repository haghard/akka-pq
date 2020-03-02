package sample.blog.eg

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }

object GameTableWriter {

  def props(gt: ActorRef) = Props(new GameTableWriter(gt))
}

class GameTableWriter(gt: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  val scheduler = context.system.scheduler

  scheduler.scheduleOnce(5.second)(self ! 0L)

  override def receive: Receive = active(0L, null)

  def active(seqNum: Long, c: Cancellable): Receive = {
    case _: Long ⇒
      gt ! sample.blog.eg.Table0.PlaceBet(seqNum, ThreadLocalRandom.current().nextLong(10l, 15l), 1)
      val next = seqNum + 1L
      //log.info("push {}", next)
      //Thread.sleep(200)
      val c = scheduler.scheduleOnce(200.millis)(self ! next)
      context.become(active(next, c))

    case sample.blog.eg.Table0.BackOff ⇒
      c.cancel()
      log.warning("BackOff !!! Slow down {}", seqNum)
      val c0 = scheduler.scheduleOnce(5000.millis)(self ! seqNum)
      context.become(active(seqNum, c0))
    case r: sample.blog.eg.Table0.BetPlacedReply ⇒
    //log.warning("Reply:  {}", r.cmdId)
  }

}