package sample.blog.processes

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }

object TableWriter {

  def props(gt: ActorRef) = Props(new TableWriter(gt))
}

class TableWriter(gt: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  val scheduler = context.system.scheduler

  scheduler.scheduleOnce(5.second)(self ! 0L)

  override def receive: Receive = active(0L, null)

  def active(cmdId: Long, c: Cancellable): Receive = {
    case _: Long ⇒
      //gt ! sample.blog.processes.Table0.PlaceBet(cmdId, ThreadLocalRandom.current().nextLong(1L, 150L), 1)
      gt ! Table0.Inc(cmdId)
      val next = cmdId + 1L
      val c = scheduler.scheduleOnce(1000.millis)(self ! next)
      context.become(active(next, c))

    case sample.blog.processes.Table0.BackOff(cmd) ⇒
      c.cancel()
      //log.warning("BackOff !!! Suspend: {}", seqNum)
      val c0 = scheduler.scheduleOnce(150.millis)(self ! cmd.cmdId)
      context.become(active(cmd.cmdId, c0))
    case _: sample.blog.processes.Table0.BetPlacedReply ⇒
    //log.warning("Reply:  {}", r.cmdId)
  }

}