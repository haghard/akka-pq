package sample.blog.processes

import akka.actor.{ ActorLogging, ActorRef, Timers }
import akka.persistence.{ AtLeastOnceDelivery, PersistentActor, RecoveryCompleted }
import Table1._
import scala.concurrent.duration._

//https://doc.akka.io/docs/alpakka/current/avroparquet.html

/**
 *
 * High-throughput persistent actor that persists in batches.
 */
object Table1 {

  sealed trait GameTableCmd1 {
    def cmdId: Long

    def playerId: Long
  }

  case class PlaceBet1(cmdId: Long, playerId: Long, chips: Int) extends GameTableCmd1

  sealed trait GameTableEvent1

  case class BetPlaced1(cmdId: Long, playerId: Long, chips: Int, deliveryId: Option[Long] = None) extends GameTableEvent1

  case class PlaceBetAccepted1(cmdId: Long, playerId: Long, chips: Int) extends GameTableEvent1

  sealed trait GameTableReply1

  case object BackOff1 extends GameTableReply1

  case class BetPlacedReply1(cmdId: Long, playerId: Long)

  case object Flush1

  case class GameTableState1(userChips: Map[Long, Int] = Map.empty)

  case class JournalWatermark(deliveryId: Long, acceptedEvn: PlaceBetAccepted1) // PlaceBet1
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

//
//Invariant: Number of chips per player should not exceed 100
//https://doc.akka.io/docs/akka/current/persistence.html

//Monolith to reactive microservices: https://www.youtube.com/watch?v=pCG2Yhe3H6g
class Table1(upstream: ActorRef, watermark: Int = 1 << 4, chipsLimitPerPlayer: Int = 100, flushPeriod: FiniteDuration = 1.second)
  extends PersistentActor with AtLeastOnceDelivery with ActorLogging with Timers {

  override val persistenceId = "gt-1" //self.path.name

  //periodic flush
  timers.startTimerAtFixedRate(persistenceId, Flush1, flushPeriod)

  // During recovery, only messages with an un-confirmed delivery id will be resent.
  // We only send JournalWatermark if at the end of receiveRecover we haven't seen JournalWatermark(id) confirmed
  override def receiveRecover: Receive = {
    var map = Map.empty[Long, Int]

    {
      case ev: PlaceBetAccepted1 ⇒
        //akka can cheat and not send JournalWatermark unless it hasn't been confirmed
        deliver(self.path)(JournalWatermark(_, ev))
      case _: JournalWatermark ⇒
      //confirmDelivery(w.deliveryId)
      case ev: BetPlaced1 ⇒
        val chips = map.getOrElse(ev.playerId, 0)
        map = map + (ev.playerId -> (chips + ev.chips))
        confirmDelivery(ev.deliveryId.get)
      case RecoveryCompleted ⇒
        context.become(active(0, Map.empty[Long, GameTableEvent1], GameTableState1(map)))
    }
  }

  override def receiveCommand = active(0, Map.empty, GameTableState1())

  def update(event: GameTableEvent1, state: GameTableState1): GameTableState1 =
    event match {
      case e: BetPlaced1 ⇒
        val curChips = state.userChips.getOrElse(e.playerId, 0)
        val updatedChips = curChips + e.chips
        if (updatedChips < chipsLimitPerPlayer) state.copy(state.userChips.updated(e.playerId, updatedChips))
        else state
      case other ⇒
        log.error(s"Unknown event $other")
        state
    }

  def active(acceptedEventsNum: Int, outstandingEvents: Map[Long, GameTableEvent1], optState: GameTableState1): Receive = {
    case cmd: PlaceBet1 ⇒
      //It will retry sending the message until the delivery is confirmed with confirmDelivery.
      val updated = acceptedEventsNum + 1
      if (updated <= watermark) {
        persist(PlaceBetAccepted1(cmd.cmdId, cmd.playerId, cmd.chips)) { accepted ⇒
          deliver(self.path)(deliveryId ⇒ JournalWatermark(deliveryId, accepted))
          val optimisticState = update(BetPlaced1(cmd.cmdId, cmd.playerId, cmd.chips), optState) //

          //confirm
          upstream ! BetPlacedReply1(cmd.cmdId, cmd.playerId)

          context become active(acceptedEventsNum, outstandingEvents, optimisticState)
        }
      } else {
        upstream ! BackOff1
        self ! Flush1
      }

    //
    case JournalWatermark(deliveryId, ev) ⇒
      //Some processing should go here
      context become active(
        acceptedEventsNum,
        outstandingEvents + (deliveryId -> BetPlaced1(ev.cmdId, ev.playerId, ev.chips, Some(deliveryId))),
        optState)

    case Flush1 if outstandingEvents.nonEmpty ⇒
      log.info("persist batch of events:{}", outstandingEvents.size)
      persistAllAsync(outstandingEvents.values.toSeq) { ev ⇒
        ev match {
          //calls for each persisted event
          case e: BetPlaced1 ⇒
            //
            val deliveryId = e.deliveryId.get
            confirmDelivery(deliveryId)
            context.become(active(acceptedEventsNum - 1, outstandingEvents - deliveryId, optState))
          case other ⇒
            log.error(s"Unknown event ${other}")
        }
      }
  }
}
