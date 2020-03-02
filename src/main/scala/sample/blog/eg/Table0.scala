package sample.blog.eg

import akka.actor.{ ActorLogging, ActorRef, Props, Timers }
import akka.persistence.PersistentActor
import sample.blog.eg.Table0._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._

//https://doc.akka.io/docs/alpakka/current/avroparquet.html

/**
 * Unreliable, high-throughput persistent actor that uses batching to persist.
 * Can lose incoming commands.
 * The goal of this is to adapt to dynamic workload.
 */
object Table0 {

  sealed trait GameTableCmd {
    def cmdId: Long

    def playerId: Long
  }

  case class PlaceBet(cmdId: Long, playerId: Long, chips: Int) extends GameTableCmd

  sealed trait GameTableEvent

  case class BetPlaced(cmdId: Long, playerId: Long, chips: Int) extends GameTableEvent

  sealed trait GameTableReply

  case class BackOff(cmd: PlaceBet) extends GameTableReply

  case class BetPlacedReply(cmdId: Long, playerId: Long)

  case object Flush

  case class GameTableState(userChips: Map[Long, Int] = Map.empty)

  object Persisted

  def props = Props(new Table0())
}

//Invariant: Number of chips per player should not exceed 100
class Table0(waterMark: Int = 8, chipsLimitPerPlayer: Int = 1000) extends Timers with PersistentActor with ActorLogging {

  override val persistenceId = "gt-0" //self.path.name

  timers.startPeriodicTimer(persistenceId, Flush, 2000.millis)

  override def receiveCommand = active(SortedMap[Long, GameTableEvent](), GameTableState(), None, 0)

  override def receiveRecover: Receive = {
    var map = Map[Long, Int]()

    {
      case ev: BetPlaced ⇒
        val chips = map.getOrElse(ev.playerId, 0)
        map = map + (ev.playerId -> (chips + ev.chips))
      case akka.persistence.RecoveryCompleted ⇒
        val s = GameTableState(map)
        log.info("RecoveryCompleted {}", s)
        context.become(active(SortedMap[Long, GameTableEvent](), s, None, 0))
    }
  }

  def update(event: GameTableEvent, state: GameTableState): GameTableState =
    event match {
      case e: BetPlaced ⇒
        val curChips = state.userChips.getOrElse(e.playerId, 0)
        val updatedChips = curChips + e.chips
        if (updatedChips < chipsLimitPerPlayer) state.copy(state.userChips.updated(e.playerId, updatedChips))
        else state
      case other ⇒
        log.error(s"Unknown event ${other}")
        state
    }

  def active(
    outstandingEvents: SortedMap[Long, GameTableEvent], optimisticState: GameTableState,
    upstream: Option[ActorRef], bSize: Int
  ): Receive = {
    case cmd: PlaceBet ⇒
      //TODO: Impl idea with 2 buffers, one for outstanding events in memory and the second one for outstanding events persisting at the moment

      if (outstandingEvents.keySet.size <= waterMark) {
        //validation should be here!!
        val ev = BetPlaced(cmd.cmdId, cmd.playerId, cmd.chips)
        //optimistically update state before persisting events
        val updatedState = update(ev, optimisticState)
        val outstandingEventsUpdated = outstandingEvents + (ev.cmdId -> ev)
        context become active(outstandingEventsUpdated, updatedState, Some(sender()), bSize)
      } else {
        //We hope that by the time we fill up current batch the prev one has already been persisted.
        upstream.foreach(_ ! BackOff(cmd))
        log.warning("BackOff - buffer size: {}: outstanding p-batch:{}. Last cmd id:{}", outstandingEvents.keySet.size, bSize, cmd.cmdId)

        if (bSize == 0) {
          self ! Flush
        }
      }

    /*
      //validation should be here!!
      //log.info("in {}", cmd.cmdId)
      val ev = BetPlaced(cmd.cmdId, cmd.playerId, cmd.chips)
      //optimistically update state before persisting events
      val updatedState = update(ev, optimisticState)
      val outstandingEventsUpdated = outstandingEvents + (ev.cmdId -> ev)

      if (outstandingEventsUpdated.keySet.size < waterMark) {
        context become active(outstandingEventsUpdated, updatedState, Some(sender()), bSize)
      } else {
        //We hope that by the time we fill up current batch the prev one has already been persisted.
        upstream.foreach(_ ! BackOff)
        log.warning("BackOff - buffer size: {}: outstanding p-batch:{}. Last cmd id:{}", outstandingEventsUpdated.keySet.size, bSize, cmd.cmdId)

        if (bSize == 0) {
          self ! Flush
        }

        context become active(outstandingEventsUpdated, updatedState, upstream, bSize)
      }*/
    case Flush if outstandingEvents.nonEmpty ⇒
      val bSize = outstandingEvents.keySet.size
      log.info("persist batch [{}]", outstandingEvents.keySet.mkString(","))
      persistAllAsync(outstandingEvents.values.toSeq) { e ⇒
        e match {
          //calls for each persisted event
          case e: BetPlaced ⇒
            Thread.sleep(41)
            //TODO: send to self and remove from buffer
            upstream.foreach(_ ! BetPlacedReply(e.cmdId, e.playerId))
            self ! Persisted
        }
      }
      context.become(active(SortedMap[Long, GameTableEvent](), optimisticState, upstream, bSize))

    case Persisted ⇒
      context.become(active(outstandingEvents, optimisticState, upstream, bSize - 1))
  }
}
