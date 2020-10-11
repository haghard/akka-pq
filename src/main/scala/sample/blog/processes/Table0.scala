package sample.blog.processes

import akka.actor.{ ActorLogging, ActorRef, Props, Timers }
import akka.persistence.PersistentActor
import akka.stream.scaladsl.RestartWithBackoffFlow
import sample.blog.processes.Table0._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._

/**
 * High-throughput persistent actor that uses batching to persist.
 * The goal of this is to adapt to dynamic workload.
 *
 *
 * https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
 * https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/
 * https://blog.colinbreck.com/akka-streams-a-motivating-example/
 *
 * https://youtu.be/MzosGtjJdPg
 *
 *
 */
object Table0 {

  sealed trait GameTableCmd {
    def cmdId: Long

    def playerId: Long
  }

  final case class PlaceBet(cmdId: Long, playerId: Long, chips: Int) extends GameTableCmd

  sealed trait GameTableEvent

  final case class BetPlaced(cmdId: Long, playerId: Long, chips: Int) extends GameTableEvent

  sealed trait GameTableReply

  final case class BackOff(cmd: PlaceBet) extends GameTableReply

  final case class BetPlacedReply(cmdId: Long /*, playerId: Long*/ )

  case object Flush

  final case class State(userChips: Map[Long, Int] = Map.empty)

  final case class Persisted(cmdId: Long)

  def props = Props(new Table0())
}

/**
 *
 * https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
 * https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/
 * the corresponding talk https://youtu.be/MzosGtjJdPg
 *
 *
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-i/
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-ii/
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-iii/
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-iv/
 *
 * https://blog.colinbreck.com/rethinking-streaming-workloads-with-akka-streams-part-ii/
 *
 * https://softwaremill.com/windowing-data-in-akka-streams/
 *
 */
class Table0(waterMark: Int = 8, chipsLimitPerPlayer: Int = 50000) extends Timers with PersistentActor with ActorLogging {

  private val flushInterval = 2000.millis

  override val persistenceId = "table-0" //self.path.name

  timers.startTimerAtFixedRate(persistenceId, Flush, flushInterval)

  override def receiveRecover: Receive = {
    var map = Map[Long, Int]()

    {
      case ev: BetPlaced ⇒
        val chips = map.getOrElse(ev.playerId, 0)
        map = map + (ev.playerId -> (chips + ev.chips))
      case akka.persistence.RecoveryCompleted ⇒
        val state = State(map)
        log.info("RecoveryCompleted {}", state)
        context.become(active(SortedMap[Long, GameTableEvent](), state, None, Set.empty[Long]))
    }
  }

  override def receiveCommand: Receive =
    active(SortedMap[Long, GameTableEvent](), State(), None, Set.empty[Long])

  //Invariant: Number of chips per player should not exceed 100
  def update(event: GameTableEvent, state: State): State =
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

  //we flush only when current persistInFlight buffer is empty
  def tryFlush(persistInFlight: Int): Unit = {
    if (persistInFlight == 0)
      self ! Flush
  }

  def active(
    outstandingEvents: SortedMap[Long, GameTableEvent], optimisticState: State,
    upstream: Option[ActorRef], persistInFlight: Set[Long]
  ): Receive = {
    case cmd: PlaceBet ⇒
      if (outstandingEvents.keySet.size <= waterMark) {
        //validation should be here!!
        val ev = BetPlaced(cmd.cmdId, cmd.playerId, cmd.chips)
        //optimistically update state before persisting events
        val updatedState = update(ev, optimisticState)
        val outstandingEventsUpdated = outstandingEvents + (ev.cmdId -> ev)
        context become active(outstandingEventsUpdated, updatedState, Some(sender()), persistInFlight)
      } else {
        upstream.foreach(_ ! BackOff(cmd))
        log.warning("BackOff cmd:{}  buffers[{}] - [{}]", cmd.cmdId, outstandingEvents.keySet.mkString(","), persistInFlight.mkString(","))
        tryFlush(persistInFlight.size)
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
      log.info("Start persisting batch [{}]", outstandingEvents.keySet.mkString(","))
      persistAllAsync(outstandingEvents.values.toSeq) { e ⇒
        e match {
          //calls for each persisted event
          case e: BetPlaced ⇒
            // Who knows how long to sleep ¯\_(ツ)_/¯?
            Thread.sleep(70)
            self ! Persisted(e.cmdId)
        }
      }
      context.become(active(SortedMap[Long, GameTableEvent](), optimisticState, upstream, outstandingEvents.keySet))

    case Persisted(cmdId) ⇒
      val inFlight = persistInFlight - cmdId
      //upstream.foreach(_ ! BetPlacedReply(cmdId))
      tryFlush(inFlight.size)
      context.become(active(outstandingEvents, optimisticState, upstream, inFlight))
  }
}
