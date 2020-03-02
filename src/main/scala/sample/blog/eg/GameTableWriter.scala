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
      gt ! sample.blog.eg.Table0.PlaceBet(seqNum, ThreadLocalRandom.current().nextLong(10L, 15L), 1)
      val next = seqNum + 1L
      val c = scheduler.scheduleOnce(50.millis)(self ! next)
      context.become(active(next, c))

    case sample.blog.eg.Table0.BackOff(cmd) ⇒
      c.cancel()
      //log.warning("BackOff !!! Suspend: {}", seqNum)
      val c0 = scheduler.scheduleOnce(200.millis)(self ! cmd.cmdId)
      context.become(active(cmd.cmdId, c0))
    case r: sample.blog.eg.Table0.BetPlacedReply ⇒
    //log.warning("Reply:  {}", r.cmdId)
  }

}